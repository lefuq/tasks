package ru.tz.exchange;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.tz.exchange.adapter.postgres.*;
import ru.tz.exchange.application.*;
import ru.tz.exchange.domain.*;

@Testcontainers
class PersistenceIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("restart_test")
          .withUsername("test")
          .withPassword("test")
          .withReuse(false);

  final AtomicReference<Instant> clock =
      new AtomicReference<>(Instant.parse("2026-11-14T00:00:00Z"));
  final Ports.External external = mock(Ports.External.class);

  record Context(
      Ports.Store store,
      Ports.Directory directory,
      TaskService tasks,
      ExchangeService exchange,
      Administration administration,
      JdbcTemplate jdbc) {}

  Context context(String url) {
    var source = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    var jdbc = new JdbcTemplate(source);
    Ports.Encoding encoding = new JsonEncoding(new ObjectMapper().findAndRegisterModules());
    Ports.Store store = new JdbcStore(jdbc, encoding);
    Ports.Directory directory = new JdbcDirectory(jdbc);
    var template = new TransactionTemplate(new DataSourceTransactionManager(source));
    Ports.Transactions transactions =
        new Ports.Transactions() {
          public <T> T run(Supplier<T> action) {
            return template.execute(status -> action.get());
          }
        };
    var tasks = new TaskService(store, transactions, clock::get, UUID::randomUUID, encoding);
    var exchange =
        new ExchangeService(
            store,
            transactions,
            clock::get,
            UUID::randomUUID,
            external,
            encoding,
            new RetryPolicy(() -> 2));
    return new Context(
        store,
        directory,
        tasks,
        exchange,
        new Administration(directory, store, transactions, tasks),
        jdbc);
  }

  String currentUrl() {
    var inspection =
        POSTGRES.getDockerClient().inspectContainerCmd(POSTGRES.getContainerId()).exec();
    String port =
        inspection
            .getNetworkSettings()
            .getPorts()
            .getBindings()
            .get(ExposedPort.tcp(5432))[0]
            .getHostPortSpec();
    return "jdbc:postgresql://"
        + POSTGRES.getHost()
        + ":"
        + port
        + "/"
        + POSTGRES.getDatabaseName();
  }

  String restartDatabase() throws Exception {
    POSTGRES.getDockerClient().restartContainerCmd(POSTGRES.getContainerId()).exec();
    Instant deadline = Instant.now().plusSeconds(30);
    while (Instant.now().isBefore(deadline)) {
      String url = currentUrl();
      try (var connection =
              DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
          var query = connection.createStatement();
          var rows = query.executeQuery("select 1")) {
        if (rows.next()) return url;
      } catch (java.sql.SQLException error) {
        Thread.sleep(100);
      }
    }
    throw new IllegalStateException("Own test PostgreSQL did not restart");
  }

  TaskService.View command(
      Context context, Actor actor, TaskService.View task, String action, Long worker) {
    return context
        .tasks()
        .command(
            actor,
            task.id(),
            action,
            UUID.randomUUID().toString(),
            new TaskService.Command(task.version(), task.description(), task.type(), worker));
  }

  @Test
  void databaseRestartPreservesScheduleKeySnapshotAuditAndIdempotency() throws Exception {
    Context original = context(POSTGRES.getJdbcUrl());
    original
        .administration()
        .bootstrap(
            new BCryptPasswordEncoder().encode("test"), "http://worker-one", "http://worker-two");
    Actor author = original.directory().identity("initiator1").actor();
    Actor operator = original.directory().identity("operator1").actor();
    TaskService.View created =
        original
            .tasks()
            .create(author, "stable-create", "Persistent", "snapshot", Task.Type.collect);
    TaskService.View selected = command(original, operator, created, "worker", 1L);
    TaskService.View sent = command(original, operator, selected, "send", null);
    String key = original.store().operation(sent.operationId()).key;
    when(external.request(any(), any(), eq(true)))
        .thenReturn(new Ports.Reply(503, "temporary", false));
    original.exchange().tick();
    assertThat(
            original
                .jdbc()
                .queryForObject("select due_at from exchange_jobs", java.sql.Timestamp.class)
                .toInstant())
        .isEqualTo(clock.get().plusSeconds(7));
    assertThat(original.jdbc().queryForObject("select failures from exchange_jobs", Integer.class))
        .isEqualTo(1);
    Context restored = context(restartDatabase());
    assertThat(
            restored
                .tasks()
                .create(author, "stable-create", "Persistent", "snapshot", Task.Type.collect))
        .isEqualTo(created);
    assertThat(restored.jdbc().queryForObject("select count(*) from tasks", Integer.class))
        .isEqualTo(1);
    Operation operation = restored.store().operation(sent.operationId());
    assertThat(operation.key).isEqualTo(key);
    assertThat(operation.description).isEqualTo("snapshot");
    assertThat(restored.exchange().tick()).isFalse();
    assertThat(restored.tasks().history(author, sent.id()))
        .extracting(Ports.History::action)
        .contains("created", "assigned", "send", "exchange_attempt", "problem");
    when(external.request(any(), any(), eq(true))).thenReturn(new Ports.Reply(202, "ack", false));
    when(external.acceptedId("ack")).thenReturn("restored-id");
    clock.set(clock.get().plusSeconds(8));
    assertThat(restored.exchange().tick()).isTrue();
    when(external.request(any(), any(), eq(false)))
        .thenReturn(new Ports.Reply(200, "ready", false));
    when(external.result("ready")).thenReturn(new ExternalResult("restored-id", "fail", 10, ""));
    clock.set(clock.get().plusSeconds(11));
    assertThat(restored.exchange().tick()).isTrue();
    TaskService.View ready = restored.tasks().get(operator, sent.id());
    assertThat(ready.state()).isEqualTo(Task.State.AWAITING_ACCEPTANCE);
    assertThat(command(restored, operator, ready, "accept", null).state())
        .isEqualTo(Task.State.COMPLETED);
    assertThat(restored.jdbc().queryForObject("select count(*) from exchange_jobs", Integer.class))
        .isZero();
    assertThat(
            restored.jdbc().queryForObject("select count(*) from exchange_attempts", Integer.class))
        .isEqualTo(3);
  }
}

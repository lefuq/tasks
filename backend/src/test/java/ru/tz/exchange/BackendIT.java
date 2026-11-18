package ru.tz.exchange;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.tz.exchange.application.*;
import ru.tz.exchange.config.Application;
import ru.tz.exchange.domain.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@Testcontainers
@SpringBootTest(
    classes = {Application.class, BackendIT.Boundaries.class},
    properties = {"app.exchange-enabled=false"})
@AutoConfigureMockMvc
class BackendIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("backend_test")
          .withUsername("test")
          .withPassword("test")
          .withReuse(false);

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @TestConfiguration
  static class Boundaries {
    @Bean
    @Primary
    Ports.External testExternal() {
      return mock(Ports.External.class);
    }
  }

  @Autowired TaskService tasks;
  @Autowired Administration administration;
  @Autowired Ports.Store store;
  @Autowired Ports.Transactions transactions;
  @Autowired Ports.External external;
  @Autowired ExchangeService exchange;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwords;
  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  final Actor author = new Actor(1, 1, Actor.Role.INITIATOR, "Author");
  final Actor operator = new Actor(10, 1, Actor.Role.OPERATOR, "Operator");

  @BeforeEach
  void resetDatabase() {
    jdbc.execute(
        "truncate"
            + " services,users,workers,tasks,operations,exchange_jobs,exchange_attempts,problems,reconciliations,audit,api_requests,spring_session"
            + " restart identity cascade");
    jdbc.update("insert into services(id,name) values(1,'A'),(2,'B')");
    String hash = passwords.encode("password");
    for (long id : new long[] {1, 2, 3, 10, 20})
      jdbc.update(
          "insert into users(id,service_id,username,name,password_hash,role) values(?,?,?,?,?,?)",
          id,
          id == 3 ? 2 : 1,
          "user" + id,
          "User " + id,
          hash,
          id >= 10 ? "OPERATOR" : "INITIATOR");
    jdbc.update(
        "insert into workers(id,name,endpoint)"
            + " values(1,'One','http://emulator:8081/workers/1'),(2,'Two','http://emulator:8081/workers/2')");
    jdbc.execute("select setval(pg_get_serial_sequence('users','id'),20)");
    reset(external);
  }

  TaskService.View create(String key) {
    return tasks.create(author, key, "Task", "", Task.Type.process);
  }

  TaskService.View command(Actor actor, TaskService.View task, String action, Long worker) {
    return tasks.command(
        actor,
        task.id(),
        action,
        UUID.randomUUID().toString(),
        new TaskService.Command(task.version(), task.description(), task.type(), worker));
  }

  TaskService.View send() {
    TaskService.View task = create(UUID.randomUUID().toString());
    Actor assigned = new Actor(task.operatorId(), 1, Actor.Role.OPERATOR, "Operator");
    task = command(assigned, task, "worker", 1L);
    return command(assigned, task, "send", null);
  }

  @Test
  void migrationAndAssignmentIncludeReturnedButExcludeCompleted() {
    TaskService.View first = create("first");
    assertThat(first.operatorId()).isEqualTo(10L);
    assertThat(create("second").operatorId()).isEqualTo(20L);
    jdbc.update("update tasks set state='RETURNED' where id=?", first.id());
    assertThat(create("third").operatorId()).isEqualTo(10L);
    jdbc.update("update tasks set state='COMPLETED' where operator_id=10");
    assertThat(create("fourth").operatorId()).isEqualTo(10L);
    Actor secondService = new Actor(3, 2, Actor.Role.INITIATOR, "B");
    TaskService.View backlog =
        tasks.create(secondService, "backlog", "Backlog", "", Task.Type.collect);
    assertThat(backlog.state()).isEqualTo(Task.State.NEW);
    jdbc.update(
        "insert into users(id,service_id,username,name,password_hash,role)"
            + " values(30,2,'new','New','hash','OPERATOR')");
    tasks.assignBacklog(2);
    assertThat(tasks.get(secondService, backlog.id()).operatorId()).isEqualTo(30L);
  }

  @Test
  void idempotencyConflictAndPagination() {
    TaskService.View first = create("key");
    assertThat(create("key")).isEqualTo(first);
    assertThat(jdbc.queryForObject("select count(*) from tasks", Integer.class)).isEqualTo(1);
    assertThatThrownBy(() -> tasks.create(author, "key", "Other", "", Task.Type.process))
        .isInstanceOf(Failure.class);
    assertThat(tasks.list(author, Task.Type.process, 0, 20).total()).isEqualTo(1);
    assertThat(tasks.list(author, null, 0, 20).items()).hasSize(1);
    assertThat(tasks.list(new Actor(2, 1, Actor.Role.INITIATOR, "Other"), null, 0, 20).items())
        .isEmpty();
  }

  @Test
  void isolationIncludesHistoryAndFile() {
    TaskService.View task = create("isolation");
    for (Actor stranger :
        List.of(
            new Actor(3, 2, Actor.Role.OPERATOR, "B"),
            new Actor(2, 1, Actor.Role.INITIATOR, "Other"))) {
      assertThatThrownBy(() -> tasks.get(stranger, task.id())).isInstanceOf(Failure.class);
      assertThatThrownBy(() -> tasks.history(stranger, task.id())).isInstanceOf(Failure.class);
      assertThatThrownBy(() -> tasks.file(stranger, task.id())).isInstanceOf(Failure.class);
    }
    assertThatThrownBy(
            () -> command(new Actor(20, 1, Actor.Role.OPERATOR, "Other"), task, "worker", 1L))
        .isInstanceOf(Failure.class);
  }

  @Test
  void commandAndAuditRollbackWithQueueFailure() {
    TaskService.View initial = command(operator, create("atomic"), "worker", 1L);
    int auditBefore = jdbc.queryForObject("select count(*) from audit", Integer.class);
    jdbc.execute("alter table exchange_jobs add constraint injected_failure check(false)");
    try {
      assertThatThrownBy(() -> command(operator, initial, "send", null))
          .isInstanceOf(RuntimeException.class);
      assertThat(tasks.get(author, initial.id()).state()).isEqualTo(Task.State.WAITING_TO_SEND);
      assertThat(jdbc.queryForObject("select count(*) from operations", Integer.class)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from audit", Integer.class))
          .isEqualTo(auditBefore);
    } finally {
      jdbc.execute("alter table exchange_jobs drop constraint injected_failure");
    }
  }

  @Test
  void parallelAssignmentIsSerializedAndCommandsDoNotLoseChanges() throws Exception {
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      List<Callable<TaskService.View>> commands = new ArrayList<>();
      for (int index = 0; index < 20; index++) {
        String key = "parallel-" + index;
        Actor actor = index % 2 == 0 ? author : new Actor(2, 1, Actor.Role.INITIATOR, "Second");
        commands.add(() -> tasks.create(actor, key, "Parallel", "", Task.Type.collect));
      }
      for (Future<TaskService.View> future : pool.invokeAll(commands))
        future.get(20, TimeUnit.SECONDS);
      assertThat(
              jdbc.queryForObject("select count(*) from tasks where operator_id=10", Integer.class))
          .isEqualTo(10);
      assertThat(
              jdbc.queryForObject("select count(*) from tasks where operator_id=20", Integer.class))
          .isEqualTo(10);
      TaskService.View task = create("race");
      TaskService.View selected = command(operator, task, "worker", 1L);
      var edit =
          pool.submit(
              () ->
                  tasks.command(
                      author,
                      selected.id(),
                      "edit",
                      "edit-race",
                      new TaskService.Command(
                          selected.version(), "edited", Task.Type.research, null)));
      var send = pool.submit(() -> command(operator, selected, "send", null));
      int successes = 0;
      for (Future<?> future : List.of(edit, send)) {
        try {
          future.get(20, TimeUnit.SECONDS);
          successes++;
        } catch (ExecutionException exception) {
          assertThat(exception.getCause()).isInstanceOf(Failure.class);
        }
      }
      assertThat(successes).isEqualTo(1);
    }
  }

  @Test
  void persistedLeaseRecoveryRejectsPriorOwner() {
    TaskService.View task = send();
    Instant now = Instant.now();
    Ports.Job first =
        transactions.run(() -> store.claim(now, now.plusSeconds(30), UUID.randomUUID()));
    assertThat(first).isNotNull();
    assertThat(transactions.run(() -> store.claim(now, now.plusSeconds(30), UUID.randomUUID())))
        .isNull();
    Ports.Job recovered =
        transactions.run(
            () -> store.claim(now.plusSeconds(31), now.plusSeconds(61), UUID.randomUUID()));
    assertThat(recovered.operationId()).isEqualTo(task.operationId());
    assertThat(transactions.run(() -> store.owns(first, now))).isFalse();
    assertThat(store.operation(task.operationId()).key).isNotBlank();
    exchange.apply(first, new Ports.Reply(503, "late", false), true);
    assertThat(jdbc.queryForObject("select count(*) from exchange_attempts", Integer.class))
        .isZero();
  }

  void readyQueue() {
    jdbc.update(
        "update exchange_jobs set due_at=now()-interval '1"
            + " second',locked_until=null,lease_token=null");
  }

  @Test
  void externalMockIsOutsideTransactionAndFailIsFinalAfterAcceptance() {
    TaskService.View task = send();
    when(external.request(any(), any(), eq(true)))
        .thenAnswer(
            invocation -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return new Ports.Reply(202, "ack", false);
            });
    when(external.acceptedId("ack")).thenReturn("external");
    when(external.request(any(), any(), eq(false))).thenReturn(new Ports.Reply(200, "fail", false));
    when(external.result("fail")).thenReturn(new ExternalResult("external", "fail", 17, ""));
    assertThat(exchange.tick()).isTrue();
    readyQueue();
    assertThat(exchange.tick()).isTrue();
    TaskService.View received = tasks.get(operator, task.id());
    assertThat(received.state()).isEqualTo(Task.State.AWAITING_ACCEPTANCE);
    assertThat(tasks.file(author, task.id())).isEmpty();
    TaskService.View completed = command(operator, received, "accept", null);
    assertThat(completed.state()).isEqualTo(Task.State.COMPLETED);
    assertThatThrownBy(() -> command(author, completed, "edit", null)).isInstanceOf(Failure.class);
    assertThat(jdbc.queryForObject("select count(*) from exchange_jobs", Integer.class)).isZero();
    assertThat(tasks.history(author, task.id()))
        .extracting(Ports.History::action)
        .contains("send", "exchange_attempt", "result_received", "accept");
  }

  @Test
  void loginCsrfSessionAndAuthorization() throws Exception {
    http.perform(get("/api/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("X-CSRF-TOKEN"));
    http.perform(
            post("/api/session")
                .contentType("application/json")
                .content("{\"username\":\"user1\",\"password\":\"password\"}"))
        .andExpect(status().isForbidden());
    http.perform(
            post("/api/session")
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"user1\",\"password\":\"bad\"}"))
        .andExpect(status().isUnauthorized());
    http.perform(
            post("/api/session")
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"user1\",\"password\":\"password\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1));
    http.perform(get("/api/tasks")).andExpect(status().isUnauthorized());
    http.perform(get("/api/workers").with(user("user1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].endpoint").doesNotExist());
    http.perform(
            post("/api/tasks")
                .with(user("user1"))
                .with(csrf())
                .header("Idempotency-Key", "http")
                .contentType("application/json")
                .content("{\"name\":\"Http\",\"description\":\"\",\"type\":\"process\"}"))
        .andExpect(status().isCreated());
    http.perform(
            post("/api/tasks")
                .with(user("user1"))
                .with(csrf())
                .header("Idempotency-Key", "missing")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isUnprocessableEntity());
    http.perform(
            post("/api/tasks")
                .with(user("user1"))
                .with(csrf())
                .header("Idempotency-Key", "invalid")
                .contentType("application/json")
                .content("{"))
        .andExpect(status().isBadRequest());
  }

  Map<String, List<Map<String, Object>>> businessSnapshot() {
    Map<String, List<Map<String, Object>>> snapshot = new TreeMap<>();
    for (String table : List.of("tasks", "operations", "exchange_jobs", "exchange_attempts", "problems", "reconciliations", "audit", "api_requests"))
      snapshot.put(table, jdbc.queryForList("select * from " + table + " order by 1"));
    return snapshot;
  }

  @Test
  void problemListFiltersAuthorsAndServicesButIncludesUnassignedOperators() throws Exception {
    jdbc.update("insert into users(id,service_id,username,name,password_hash,role) values(30,2,'user30','Foreign operator',?,'OPERATOR')", passwords.encode("password"));
    TaskService.View task = send();
    when(external.request(any(), any(), anyBoolean())).thenReturn(new Ports.Reply(503, "temporary", false));
    exchange.tick();
    var snapshot = businessSnapshot();
    for (String username : List.of("user1", "user10", "user20"))
      http.perform(get("/api/problems").with(user(username)))
          .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(task.id()))
          .andExpect(jsonPath("$.length()").value(1));
    for (String username : List.of("user2", "user3", "user30"))
      http.perform(get("/api/problems").with(user(username)))
          .andExpect(status().isOk()).andExpect(content().json("[]"));
    assertThat(businessSnapshot()).isEqualTo(snapshot);
  }

  @Test
  void diagnosticsContainFailuresButNoSecretMarkers(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
    String password = "QA02-password-secret";
    String session = "QA02-session-secret";
    String csrfSecret = "QA02-csrf-secret";
    String externalSecret = "QA02-external-secret";
    http.perform(post("/api/session").with(csrf()).contentType("application/json")
            .cookie(new jakarta.servlet.http.Cookie("SESSION", session))
            .content(mapper.writeValueAsString(Map.of("username", "user1", "password", password))))
        .andExpect(status().isUnauthorized());
    http.perform(post("/api/tasks").with(user("user1")).header("X-CSRF-TOKEN", csrfSecret)
            .contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    send();
    when(external.request(any(), any(), eq(true))).thenReturn(new Ports.Reply(0, externalSecret, true));
    exchange.tick();
    readyQueue();
    when(external.request(any(), any(), anyBoolean())).thenReturn(new Ports.Reply(503, externalSecret, false));
    exchange.tick();
    readyQueue();
    when(external.request(any(), any(), anyBoolean())).thenReturn(new Ports.Reply(202, externalSecret, false));
    when(external.acceptedId(externalSecret)).thenThrow(new Failure("InvalidResult"));
    exchange.tick();
    assertThat(output.getAll()).contains("Authentication rejected", "Access denied: CSRF", "Exchange failure: NetworkFailure", "Exchange failure: TemporaryFailure", "Exchange failure: InvalidResult")
        .doesNotContain(password, session, csrfSecret, externalSecret);
  }

  @Test
  void allProtectedHttpEndpointsRejectAnonymousAndMutationsRejectMissingCsrf() throws Exception {
    var snapshot = businessSnapshot();
    for (String path : List.of("/api/me", "/api/workers", "/api/tasks", "/api/problems", "/api/tasks/1", "/api/tasks/1/history", "/api/tasks/1/result/file")) {
      http.perform(get(path)).andExpect(status().isUnauthorized());
      assertThat(businessSnapshot()).isEqualTo(snapshot);
    }
    for (String path : List.of("/api/tasks", "/api/tasks/1/send", "/api/tasks/1/retry", "/api/tasks/1/return", "/api/tasks/1/accept", "/api/tasks/1/reconcile", "/api/tasks/1", "/api/tasks/1/worker")) {
      var anonymous = path.endsWith("/worker") ? put(path) : path.equals("/api/tasks/1") ? patch(path) : post(path);
      http.perform(anonymous.with(csrf()).contentType("application/json").content("{}"))
          .andExpect(status().isUnauthorized());
      for (String username : List.of("user1", "user2", "user3", "user10", "user20")) {
        var request = path.endsWith("/worker") ? put(path) : path.equals("/api/tasks/1") ? patch(path) : post(path);
        http.perform(request.with(user(username)).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        assertThat(businessSnapshot()).isEqualTo(snapshot);
      }
    }
    http.perform(delete("/api/session").with(user("user1"))).andExpect(status().isForbidden());
    http.perform(post("/api/session").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    assertThat(businessSnapshot()).isEqualTo(snapshot);
  }

  @Test
  void negativeHttpMatrixPreservesEveryBusinessTable() throws Exception {
    jdbc.update("insert into users(id,service_id,username,name,password_hash,role) values(30,2,'user30','Foreign operator',?,'OPERATOR')", passwords.encode("password"));
    TaskService.View task = send();
    when(external.request(any(), any(), eq(true))).thenReturn(new Ports.Reply(202, "ack", false));
    when(external.acceptedId("ack")).thenReturn("external");
    when(external.request(any(), any(), eq(false))).thenReturn(new Ports.Reply(200, "ready", false));
    when(external.result("ready")).thenReturn(new ExternalResult("external", "success", 1, "cHJpdmF0ZS1maWxl"));
    exchange.tick();
    readyQueue();
    exchange.tick();
    task = tasks.get(author, task.id());
    var snapshot = businessSnapshot();
    String base = "/api/tasks/" + task.id();
    for (String username : List.of("user2", "user3", "user30")) {
      for (String suffix : List.of("", "/history", "/result/file")) {
        http.perform(get(base + suffix).with(user(username)))
            .andExpect(status().isForbidden())
            .andExpect(content().json("{\"code\":\"Forbidden\"}", true))
            .andExpect(header().doesNotExist("Content-Disposition"));
        assertThat(businessSnapshot()).isEqualTo(snapshot);
      }
    }
    for (String action : List.of("worker", "send", "retry", "return", "accept", "reconcile", "edit")) {
      List<String> denied = action.equals("edit")
          ? List.of("user2", "user3", "user10", "user20", "user30")
          : List.of("user1", "user2", "user3", "user20", "user30");
      for (String username : denied) {
        var request = action.equals("edit") ? patch(base) : action.equals("worker") ? put(base + "/worker") : post(base + "/" + action);
        http.perform(request.with(user(username)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(mapper.writeValueAsString(action.equals("edit") ? Map.of("version", task.version(), "description", "changed", "type", "process") : action.equals("worker") ? Map.of("version", task.version(), "workerId", 1) : Map.of("version", task.version()))))
            .andExpect(status().isForbidden())
            .andExpect(content().json("{\"code\":\"Forbidden\"}", true));
        assertThat(businessSnapshot()).isEqualTo(snapshot);
      }
    }
    for (String username : List.of("user10", "user20", "user30")) {
      http.perform(post("/api/tasks").with(user(username)).with(csrf())
              .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
              .content("{\"name\":\"Denied\",\"description\":\"\",\"type\":\"process\"}"))
          .andExpect(status().isForbidden());
      assertThat(businessSnapshot()).isEqualTo(snapshot);
    }
    for (String username : List.of("user1", "user10", "user20"))
      for (String suffix : List.of("", "/history", "/result/file"))
        http.perform(get(base + suffix).with(user(username))).andExpect(status().isOk());
    for (String username : List.of("user2", "user3", "user30"))
      http.perform(get("/api/tasks").with(user(username)))
          .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
  }

  @Test
  void otherTypeIsRejectedByHttpDeserializationWithoutWrites() throws Exception {
    http.perform(
            post("/api/tasks")
                .with(user("user1"))
                .with(csrf())
                .header("Idempotency-Key", "other-type")
                .contentType("application/json")
                .content("{\"name\":\"Http\",\"description\":\"\",\"type\":\"other\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MalformedRequest"))
        .andExpect(response -> assertThat(response.getResolvedException())
            .isInstanceOf(org.springframework.http.converter.HttpMessageNotReadableException.class));
    for (String table : List.of("tasks", "operations", "audit", "api_requests"))
      assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).isZero();
  }

  @Test
  void httpCreationWithoutOperatorIsAssignedByCreateOperatorCommand() throws Exception {
    var response = http.perform(
            post("/api/tasks")
                .with(user("user3"))
                .with(csrf())
                .header("Idempotency-Key", "http-backlog")
                .contentType("application/json")
                .content("{\"name\":\"Backlog\",\"description\":\"\",\"type\":\"collect\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("NEW"))
        .andExpect(jsonPath("$.operatorId").isEmpty())
        .andReturn();
    long id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
    long operatorId = administration.createOperator(2, "backlog-operator", "Operator", passwords.encode("test"));
    http.perform(get("/api/tasks/" + id).with(user("user3")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING_TO_SEND"))
        .andExpect(jsonPath("$.operatorId").value(operatorId));
  }

  @Test
  void bootstrapAndOperatorCommandKeepBacklogAssignmentAtomic() {
    jdbc.execute("truncate services,workers restart identity cascade");
    administration.bootstrap(passwords.encode("demo"), "http://worker/one", "http://worker/two");
    assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(5);
    assertThat(jdbc.queryForObject("select count(*) from operations", Integer.class)).isZero();
    administration.demoBaseline();
    long serviceId =
        jdbc.queryForObject("select service_id from users where username='initiator3'", Long.class);
    long operatorId =
        administration.createOperator(serviceId, "new-operator", "New", passwords.encode("demo"));
    assertThat(
            jdbc.queryForObject(
                "select operator_id from tasks where service_id=?", Long.class, serviceId))
        .isEqualTo(operatorId);
    assertThat(
            jdbc.queryForObject(
                "select state from tasks where service_id=?", String.class, serviceId))
        .isEqualTo("WAITING_TO_SEND");
  }

  @Test
  void reconciliationPreservesBothResultsUntilExplicitAcceptanceAndCompletion() {
    TaskService.View sent = send();
    when(external.request(any(), any(), eq(true))).thenReturn(new Ports.Reply(202, "ack", false));
    when(external.acceptedId("ack")).thenReturn("external");
    when(external.request(any(), any(), eq(false)))
        .thenReturn(new Ports.Reply(200, "success", false));
    when(external.result("success"))
        .thenReturn(new ExternalResult("external", "success", 10, "QQ=="));
    exchange.tick();
    readyQueue();
    exchange.tick();
    TaskService.View original = tasks.get(operator, sent.id());
    command(operator, original, "reconcile", null);
    when(external.request(any(), any(), eq(false))).thenReturn(new Ports.Reply(200, "fail", false));
    when(external.result("fail")).thenReturn(new ExternalResult("external", "fail", 11, ""));
    exchange.tick();
    TaskService.View mismatch = tasks.get(operator, sent.id());
    assertThat(mismatch.result().status()).isEqualTo("success");
    assertThat(mismatch.problem()).isEqualTo(Operation.Problem.MISMATCH);
    assertThat(mismatch.actions()).doesNotContain("accept");
    assertThat(tasks.problems(operator)).hasSize(1);
    command(operator, mismatch, "reconcile", null);
    exchange.tick();
    TaskService.View completed = command(operator, tasks.get(operator, sent.id()), "accept", null);
    assertThat(completed.result().status()).isEqualTo("fail");
    command(operator, completed, "reconcile", null);
    when(external.request(any(), any(), eq(false)))
        .thenReturn(new Ports.Reply(200, "success", false));
    exchange.tick();
    TaskService.View after = tasks.get(operator, sent.id());
    assertThat(after.state()).isEqualTo(Task.State.COMPLETED);
    assertThat(after.result().status()).isEqualTo("fail");
    assertThat(after.actions()).containsExactly("reconcile");
    assertThat(tasks.problems(operator)).hasSize(1);
    command(operator, after, "reconcile", null);
    when(external.request(any(), any(), eq(false))).thenReturn(new Ports.Reply(200, "fail", false));
    exchange.tick();
    TaskService.View matched = tasks.get(operator, sent.id());
    assertThat(matched.state()).isEqualTo(Task.State.COMPLETED);
    assertThat(matched.result()).isEqualTo(completed.result());
    assertThat(matched.problem()).isNull();
    assertThat(tasks.problems(operator)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from problems where resolved_at is null", Integer.class))
        .isZero();

    assertThat(jdbc.queryForObject("select count(*) from reconciliations", Integer.class))
        .isEqualTo(4);
    assertThat(
            tasks.history(operator, sent.id()).stream()
                .filter(item -> item.action().equals("reconciliation"))
                .count())
        .isEqualTo(4);
  }

  @Test
  void duplicateAndHeldOldResponseUseSameApplicationHandler() {
    TaskService.View initial = send();
    when(external.result("invalid")).thenThrow(new Failure("InvalidResult"));
    Instant now = Instant.now();
    Ports.Job held =
        transactions.run(() -> store.claim(now, now.plusSeconds(30), UUID.randomUUID()));
    exchange.apply(held, new Ports.Reply(200, "invalid", false), false);
    TaskService.View invalid = tasks.get(operator, initial.id());
    assertThat(invalid.problem()).isEqualTo(Operation.Problem.INVALID_RESULT);
    TaskService.View returned = command(operator, invalid, "return", null);
    TaskService.View edited = command(author, returned, "edit", null);
    TaskService.View replacement = command(operator, edited, "send", null);
    assertThat(replacement.operationId()).isNotEqualTo(initial.operationId());
    assertThat(store.operation(replacement.operationId()).key)
        .isNotEqualTo(store.operation(initial.operationId()).key);
    exchange.apply(held, new Ports.Reply(200, "held-old-success", false), false);
    assertThat(tasks.get(author, initial.id()).operationId()).isEqualTo(replacement.operationId());
    assertThat(tasks.get(author, initial.id()).result()).isNull();
    assertThat(tasks.history(author, initial.id()))
        .anyMatch(
            item ->
                item.action().equals("discarded_response")
                    && item.details().contains("held-old-success"));
    when(external.request(any(), any(), eq(true))).thenReturn(new Ports.Reply(202, "ack", false));
    when(external.acceptedId("ack")).thenReturn("new-id");
    exchange.tick();
    readyQueue();
    Instant next = Instant.now();
    Ports.Job duplicate =
        transactions.run(() -> store.claim(next, next.plusSeconds(30), UUID.randomUUID()));
    when(external.result("valid")).thenReturn(new ExternalResult("new-id", "success", 15, "QQ=="));
    exchange.apply(duplicate, new Ports.Reply(200, "valid", false), false);
    long version = tasks.get(author, initial.id()).version();
    exchange.apply(duplicate, new Ports.Reply(200, "valid", false), false);
    assertThat(tasks.get(author, initial.id()).version()).isEqualTo(version);
    assertThat(
            tasks.history(author, initial.id()).stream()
                .filter(item -> item.action().equals("result_received"))
                .count())
        .isEqualTo(1);
  }

  @Test
  void realSessionCookiePersistsAuthenticationRotatesAndLogsOut() throws Exception {
    var initial =
        http.perform(get("/api/me")).andExpect(status().isUnauthorized()).andReturn().getResponse();
    var before = initial.getCookie("SESSION");
    assertThat(before).isNotNull();
    assertThat(before.isHttpOnly()).isTrue();
    assertThat(initial.getHeader("Set-Cookie")).contains("SameSite=Strict");
    var loggedIn =
        http.perform(
                post("/api/session")
                    .cookie(before)
                    .header("X-CSRF-TOKEN", initial.getHeader("X-CSRF-TOKEN"))
                    .contentType("application/json")
                    .content("{\"username\":\"user1\",\"password\":\"password\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var session = loggedIn.getCookie("SESSION");
    assertThat(session).isNotNull();
    assertThat(session.getValue()).isNotEqualTo(before.getValue());
    var authenticated =
        http.perform(get("/api/me").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1))
            .andReturn()
            .getResponse();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from spring_session_attributes where"
                    + " attribute_name='SPRING_SECURITY_CONTEXT'",
                Integer.class))
        .isEqualTo(1);
    http.perform(
            delete("/api/session")
                .cookie(session)
                .header("X-CSRF-TOKEN", authenticated.getHeader("X-CSRF-TOKEN")))
        .andExpect(status().isNoContent());
    http.perform(get("/api/me").cookie(session)).andExpect(status().isUnauthorized());
  }

  @Test
  void concurrentIdenticalCommandsCreateOneTaskAndReturnTheSameResponse() throws Exception {
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      List<Callable<TaskService.View>> commands = new ArrayList<>();
      for (int index = 0; index < 8; index++) commands.add(() -> create("shared-key"));
      Set<TaskService.View> responses = new HashSet<>();
      for (Future<TaskService.View> future : pool.invokeAll(commands))
        responses.add(future.get(10, TimeUnit.SECONDS));
      assertThat(responses).hasSize(1);
      assertThat(jdbc.queryForObject("select count(*) from tasks", Integer.class)).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from api_requests", Integer.class))
          .isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from audit", Integer.class)).isEqualTo(2);
    }
  }

  @Test
  void freshWorkerRestoresUnknownDeliveryUsingPersistedKeyAndSnapshot() {
    TaskService.View sent = send();
    Operation saved = store.operation(sent.operationId());
    when(external.request(any(), any(), eq(true)))
        .thenReturn(new Ports.Reply(0, "lost-ack", true), new Ports.Reply(202, "ack", false));
    when(external.acceptedId("ack")).thenReturn("recovered-id");
    when(external.request(any(), any(), eq(false)))
        .thenReturn(new Ports.Reply(200, "ready", false));
    when(external.result("ready"))
        .thenReturn(new ExternalResult("recovered-id", "success", 17, "QQ=="));
    exchange.tick();
    assertThat(tasks.get(operator, sent.id()).problem()).isEqualTo(Operation.Problem.TEMPORARY);
    assertThat(jdbc.queryForObject("select kind from exchange_jobs", String.class))
        .isEqualTo("AUTO_RECONCILE");
    var encoding = new ru.tz.exchange.adapter.postgres.JsonEncoding(mapper);
    var reloadedStore = new ru.tz.exchange.adapter.postgres.JdbcStore(jdbc, encoding);
    var restarted =
        new ExchangeService(
            reloadedStore,
            transactions,
            Instant::now,
            UUID::randomUUID,
            external,
            encoding,
            new RetryPolicy(() -> 2));
    readyQueue();
    restarted.tick();
    readyQueue();
    restarted.tick();
    assertThat(tasks.get(operator, sent.id()).state()).isEqualTo(Task.State.AWAITING_ACCEPTANCE);
    var posts = org.mockito.ArgumentCaptor.forClass(Operation.class);
    verify(external, times(2)).request(posts.capture(), any(), eq(true));
    assertThat(posts.getAllValues())
        .allSatisfy(
            operation -> {
              assertThat(operation.key).isEqualTo(saved.key);
              assertThat(operation.name).isEqualTo(saved.name);
              assertThat(operation.description).isEqualTo(saved.description);
              assertThat(operation.type).isEqualTo(saved.type);
            });
    assertThat(jdbc.queryForObject("select count(*) from operations", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from exchange_jobs", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from reconciliations", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void taskTypeNamesComeFromDatabase() throws Exception {
    jdbc.update("update task_types set name=? where code=?", "Название из БД", "process");
    try {
      TaskService.View task = create("type-name");
      assertThat(task.typeName()).isEqualTo("Название из БД");
      assertThat(tasks.get(author, task.id()).typeName()).isEqualTo("Название из БД");
      assertThat(tasks.list(author, Task.Type.process, 0, 20).items().getFirst().typeName())
          .isEqualTo("Название из БД");
      http.perform(get("/api/task-types").with(user("user1")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[?(@.code == 'process')].name").value("Название из БД"));
    } finally {
      jdbc.update("update task_types set name=? where code=?", "Обработка", "process");
    }
  }
}

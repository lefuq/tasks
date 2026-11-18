package ru.tz.exchange;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.tz.exchange.application.*;
import ru.tz.exchange.config.Application;
import ru.tz.exchange.domain.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Testcontainers
@SpringBootTest(classes = Application.class, properties = "app.exchange-enabled=false")
class JointIT {
  @Container
  static final PostgreSQLContainer<?> BACKEND_DB =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("joint_backend")
          .withUsername("test")
          .withPassword("test")
          .withReuse(false);

  @Container
  static final PostgreSQLContainer<?> EMULATOR_DB =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("joint_emulator")
          .withUsername("test")
          .withPassword("test")
          .withReuse(false);

  static Process emulator;
  static final List<String> secretMarkers = new ArrayList<>();
  static String testBackendLog = "";
  static String emulatorUrl;
  static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  static final ObjectMapper JSON = new ObjectMapper();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", BACKEND_DB::getJdbcUrl);
    registry.add("spring.datasource.username", BACKEND_DB::getUsername);
    registry.add("spring.datasource.password", BACKEND_DB::getPassword);
  }

  @BeforeAll
  static void launchEmulator() throws Exception {
    Path jar = Path.of(System.getProperty("emulator.jar"));
    assertThat(jar).exists();
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    emulatorUrl = "http://localhost:" + port;
    ProcessBuilder builder =
        new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar",
            jar.toString(),
            "--spring.datasource.url=" + EMULATOR_DB.getJdbcUrl(),
            "--spring.datasource.username=" + EMULATOR_DB.getUsername(),
            "--spring.datasource.password=" + EMULATOR_DB.getPassword(),
            "--server.port=" + port,
            "--emulator.execution-enabled=true");
    builder.environment().put("EMULATOR_DB_URL", EMULATOR_DB.getJdbcUrl());
    builder.environment().put("EMULATOR_DB_USER", EMULATOR_DB.getUsername());
    builder.environment().put("EMULATOR_DB_PASSWORD", EMULATOR_DB.getPassword());
    builder.environment().put("EMULATOR_PORT", String.valueOf(port));
    builder.environment().put("EMULATOR_EXECUTION_ENABLED", "true");
    builder
        .redirectErrorStream(true)
        .redirectOutput(Path.of("target", "joint-emulator.log").toFile());
    emulator = builder.start();
    Instant deadline = Instant.now().plusSeconds(30);
    while (Instant.now().isBefore(deadline)) {
      if (!emulator.isAlive())
        throw new IllegalStateException("Emulator exited; see target/joint-emulator.log");
      try {
        HttpResponse<String> response =
            HTTP.send(
                HttpRequest.newBuilder(URI.create(emulatorUrl + "/actuator/health"))
                    .timeout(Duration.ofSeconds(1))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) return;
      } catch (java.io.IOException ignored) {
      }
      Thread.sleep(200);
    }
    throw new IllegalStateException("Emulator startup timeout");
  }

  @AfterAll
  static void stopEmulator(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
    if (emulator != null) {
      emulator.destroy();
      if (!emulator.waitFor(5, TimeUnit.SECONDS)) emulator.destroyForcibly().waitFor();
    }
    if (!secretMarkers.isEmpty()) {
      String backendLog = testBackendLog + output.getAll();
      String emulatorLog = Files.readString(Path.of("target", "joint-emulator.log"));
      for (String secret : secretMarkers) {
        assertThat(backendLog).doesNotContain(secret);
        assertThat(emulatorLog).doesNotContain(secret);
      }
      Files.writeString(Path.of("target", "joint-backend-qa02.log"), backendLog);
    }
  }

  @Autowired org.springframework.test.web.servlet.MockMvc http;
  @Autowired JdbcTemplate jdbc;
  @Autowired TaskService tasks;
  @Autowired Administration administration;
  @Autowired Ports.Directory directory;
  @Autowired Ports.Store store;
  @Autowired Ports.Transactions transactions;
  @Autowired Ports.External external;
  @Autowired ExchangeService exchange;
  @Autowired PasswordEncoder passwords;
  Actor author;

  @BeforeEach
  void prepare() throws Exception {
    jdbc.execute("truncate services,workers restart identity cascade");
    administration.bootstrap(
        passwords.encode("joint"),
        emulatorUrl + "/workers/worker-1",
        emulatorUrl + "/workers/worker-2");
    author = directory.identity("initiator1").actor();
    configure("worker-1", "normal", "success");
    configure("worker-2", "normal", "success");
  }

  JsonNode admin(String method, String path, String body) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(emulatorUrl + path)).timeout(Duration.ofSeconds(3));
    if (method.equals("PUT"))
      builder
          .header("Content-Type", "application/json")
          .PUT(HttpRequest.BodyPublishers.ofString(body));
    else builder.GET();
    HttpResponse<String> response =
        HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  void configure(String worker, String fault, String outcome) throws Exception {
    admin(
        "PUT",
        "/admin/workers/" + worker,
        "{\"fault\":\"" + fault + "\",\"outcome\":\"" + outcome + "\"}");
  }

  Actor operator(TaskService.View task) {
    return List.of(directory.identity("operator1").actor(), directory.identity("operator2").actor())
        .stream()
        .filter(actor -> actor.id() == task.operatorId())
        .findFirst()
        .orElseThrow();
  }

  TaskService.View command(Actor actor, TaskService.View task, String action, Long worker) {
    return tasks.command(
        actor,
        task.id(),
        action,
        UUID.randomUUID().toString(),
        new TaskService.Command(task.version(), task.description(), task.type(), worker));
  }

  TaskService.View send(String name, long worker) {
    TaskService.View task =
        tasks.create(author, UUID.randomUUID().toString(), name, "", Task.Type.process);
    task = command(operator(task), task, "worker", worker);
    return command(operator(task), task, "send", null);
  }

  JsonNode awaitExternal(UUID operationId) throws Exception {
    Operation operation = store.operation(operationId);
    assertThat(operation.externalId).isNotBlank();
    Instant deadline = Instant.now().plusSeconds(90);
    while (Instant.now().isBefore(deadline)) {
      JsonNode inspection = admin("GET", "/admin/operations/" + operation.externalId, null);
      if (inspection.path("state").asText().equals("completed")) {
        assertThat(inspection.path("execution_count").asInt()).isEqualTo(1);
        return inspection;
      }
      Thread.sleep(500);
    }
    throw new IllegalStateException("External execution timeout");
  }

  void tickWhenDue() throws Exception {
    Instant deadline = Instant.now().plusSeconds(15);
    while (Instant.now().isBefore(deadline)) {
      if (exchange.tick()) return;
      Thread.sleep(20);
    }
    throw new IllegalStateException("No due queue job");
  }

  record Captured(Ports.Job job, Ports.Reply reply) {}

  Captured capture(UUID operationId) throws Exception {
    Instant now = Instant.now();
    Ports.Job job = null;
    Instant deadline = now.plusSeconds(15);
    while (job == null && Instant.now().isBefore(deadline)) {
      Instant claimAt = Instant.now();
      job =
          transactions.run(() -> store.claim(claimAt, claimAt.plusSeconds(30), UUID.randomUUID()));
      if (job == null) Thread.sleep(20);
    }
    assertThat(job).isNotNull();
    assertThat(job.operationId()).isEqualTo(operationId);
    Operation operation = store.operation(operationId);
    Ports.Reply reply = external.request(operation, store.worker(operation.workerId), false);
    assertThat(reply.status()).isEqualTo(200);
    assertThat(reply.networkFailure()).isFalse();
    return new Captured(job, reply);
  }

  void replay(Captured response) {
    replay(response.job().operationId(), response.reply());
  }

  void replay(UUID operationId, Ports.Reply reply) {
    Instant now = Instant.now();
    transactions.run(
        () -> {
          store.enqueue(operationId, "GET", now);
          return null;
        });
    Ports.Job lease =
        transactions.run(() -> store.claim(now, now.plusSeconds(30), UUID.randomUUID()));
    assertThat(lease.operationId()).isEqualTo(operationId);
    assertThat(transactions.run(() -> store.owns(lease, Instant.now()))).isTrue();
    exchange.apply(lease, reply, false);
  }

  @Test
  void completeProcessLogsRejectSecretsDuringRealFailures(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
    String password = "JOINT-QA02-password-secret";
    String cookie = "JOINT-QA02-cookie-secret";
    String token = "JOINT-QA02-csrf-secret";
    String body = "JOINT-QA02-body-secret";
    var anonymous = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/me"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized())
        .andReturn().getResponse();
    var actualSession = anonymous.getCookie("SESSION");
    String actualToken = anonymous.getHeader("X-CSRF-TOKEN");
    assertThat(actualSession).isNotNull();
    assertThat(actualToken).isNotBlank();
    secretMarkers.addAll(List.of(password, cookie, token, body, actualSession.getValue(), actualToken));
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/session")
            .cookie(actualSession).header("X-CSRF-TOKEN", actualToken)
            .contentType("application/json")
            .content(JSON.writeValueAsString(Map.of("username", "initiator1", "password", password))))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/session")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
            .cookie(new jakarta.servlet.http.Cookie("SESSION", cookie))
            .contentType("application/json")
            .content(JSON.writeValueAsString(Map.of("username", "initiator1", "password", password))))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/tasks")
            .header("X-CSRF-TOKEN", token).contentType("application/json").content("{}"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    TaskService.View task = tasks.create(author, UUID.randomUUID().toString(), "LogCheck", body, Task.Type.process);
    task = command(operator(task), task, "worker", 1L);
    task = command(operator(task), task, "send", null);
    assertThat(exchange.tick()).isTrue();
    awaitExternal(task.operationId());
    for (String fault : List.of("unavailable", "lose_response", "invalid_json")) {
      configure("worker-1", fault, "success");
      jdbc.update("update exchange_jobs set due_at=now()-interval '1 second'");
      assertThat(exchange.tick()).isTrue();
    }
    String backendLog = output.getAll();
    testBackendLog = backendLog;
    String emulatorLog = Files.readString(Path.of("target", "joint-emulator.log"));
    assertThat(backendLog).contains("Authentication rejected", "Access denied: CSRF", "Exchange failure: NetworkFailure", "Exchange failure: TemporaryFailure", "Exchange failure: InvalidResult");
    assertThat(emulatorLog).contains("status=503", "lose_response", "invalid_json");
    for (String secret : secretMarkers) {
      assertThat(backendLog).doesNotContain(secret);
      assertThat(emulatorLog).doesNotContain(secret);
    }
    Files.writeString(Path.of("target", "joint-backend-qa02.log"), backendLog);
  }

  @Test
  void heldRealResponseAndDuplicatePassTheActualHandlerWithValidReplayLeases() throws Exception {
    TaskService.View initial = send("Held-real-response", 1);
    tickWhenDue();
    JsonNode first = awaitExternal(initial.operationId());
    Captured held = capture(initial.operationId());
    assertThat(tasks.get(author, initial.id()).result()).isNull();
    configure("worker-1", "invalid_json", "success");
    command(operator(initial), tasks.get(author, initial.id()), "reconcile", null);
    tickWhenDue();
    TaskService.View invalid = tasks.get(author, initial.id());
    assertThat(invalid.problem()).isEqualTo(Operation.Problem.INVALID_RESULT);
    TaskService.View returned = command(operator(invalid), invalid, "return", null);
    TaskService.View edited = command(author, returned, "edit", null);
    configure("worker-1", "normal", "success");
    configure("worker-2", "normal", "fail");
    TaskService.View selected = command(operator(edited), edited, "worker", 2L);
    TaskService.View replacement = command(operator(selected), selected, "send", null);
    assertThat(replacement.operationId()).isNotEqualTo(initial.operationId());
    assertThat(store.operation(replacement.operationId()).key)
        .isNotEqualTo(store.operation(initial.operationId()).key);
    tickWhenDue();
    JsonNode second = awaitExternal(replacement.operationId());
    Captured current = capture(replacement.operationId());
    exchange.apply(current.job(), current.reply(), false);
    TaskService.View result = tasks.get(author, initial.id());
    assertThat(result.result().status()).isEqualTo("fail");
    assertThat(result.result().data()).isEmpty();
    replay(current);
    assertThat(tasks.get(author, initial.id()).version()).isEqualTo(result.version());
    replay(held);
    TaskService.View after = tasks.get(author, initial.id());
    assertThat(after.operationId()).isEqualTo(replacement.operationId());
    assertThat(after.result()).isEqualTo(result.result());
    assertThat(tasks.history(author, initial.id()))
        .anyMatch(
            event ->
                event.action().equals("stale_response")
                    && event.details().contains(first.path("id").asText()));
    assertThat(
            tasks.history(author, initial.id()).stream()
                .filter(event -> event.action().equals("result_received"))
                .count())
        .isEqualTo(1);
    assertThat(first.path("execution_count").asInt()).isEqualTo(1);
    assertThat(second.path("execution_count").asInt()).isEqualTo(1);
    TaskService.View completed = command(operator(after), after, "accept", null);
    assertThat(completed.state()).isEqualTo(Task.State.COMPLETED);
    assertThat(tasks.file(author, initial.id())).isEmpty();
  }

  @Test
  void workerFailuresPreserveKeyAndLostResultCreatesNewOperation() throws Exception {
    configure("worker-1", "unavailable", "success");
    TaskService.View task = send("Recovery-real-response", 1);
    String key = store.operation(task.operationId()).key;
    tickWhenDue();
    TaskService.View failed = tasks.get(author, task.id());
    assertThat(failed.problem()).isEqualTo(Operation.Problem.TEMPORARY);
    command(operator(failed), failed, "worker", 2L);
    tickWhenDue();
    JsonNode inspection = awaitExternal(task.operationId());
    configure("worker-2", "lose_response", "success");
    long started = System.nanoTime();
    tickWhenDue();
    assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isBetween(4500L, 8000L);
    TaskService.View unknown = tasks.get(author, task.id());
    assertThat(unknown.exchange()).isEqualTo(Operation.Exchange.UNKNOWN);
    assertThat(unknown.externalId()).isEqualTo(inspection.path("id").asText());
    configure("worker-1", "normal", "success");
    command(operator(unknown), unknown, "worker", 1L);
    tickWhenDue();
    TaskService.View recovered = tasks.get(author, task.id());
    assertThat(recovered.state()).isEqualTo(Task.State.AWAITING_ACCEPTANCE);
    assertThat(recovered.operationId()).isEqualTo(task.operationId());
    assertThat(store.operation(task.operationId()).key).isEqualTo(key);
    assertThat(
            admin("GET", "/admin/operations/" + recovered.externalId(), null)
                .path("execution_count")
                .asInt())
        .isEqualTo(1);
    assertThat(recovered.result().status()).isEqualTo("success");
    Operation original = store.operation(task.operationId());
    Ports.Reply held = external.request(original, store.worker(original.workerId), false);
    assertThat(held.status()).isEqualTo(200);
    configure("worker-1", "lost_result", "success");
    command(operator(recovered), recovered, "reconcile", null);
    tickWhenDue();
    TaskService.View lost = tasks.get(operator(recovered), task.id());
    assertThat(lost.problem()).isEqualTo(Operation.Problem.LOST_RESULT);
    assertThat(lost.actions()).contains("worker", "send").doesNotContain("retry", "accept");
    configure("worker-2", "normal", "fail");
    TaskService.View selected = command(operator(lost), lost, "worker", 2L);
    TaskService.View replacement = command(operator(selected), selected, "send", null);
    assertThat(replacement.operationId()).isNotEqualTo(task.operationId());
    assertThat(store.operation(replacement.operationId()).key).isNotEqualTo(key);
    tickWhenDue();
    JsonNode replacementInspection = awaitExternal(replacement.operationId());
    tickWhenDue();
    TaskService.View current = tasks.get(author, task.id());
    assertThat(current.result().status()).isEqualTo("fail");
    assertThat(current.externalId()).isNotEqualTo(original.externalId);
    replay(task.operationId(), held);
    assertThat(tasks.get(author, task.id()).result()).isEqualTo(current.result());
    assertThat(tasks.get(author, task.id()).operationId()).isEqualTo(replacement.operationId());
    assertThat(tasks.history(author, task.id()))
        .anyMatch(
            event ->
                event.action().equals("stale_response")
                    && event.details().contains(original.externalId));
    assertThat(
            admin("GET", "/admin/operations/" + original.externalId, null)
                .path("execution_count")
                .asInt())
        .isEqualTo(1);
    assertThat(replacementInspection.path("execution_count").asInt()).isEqualTo(1);
  }

  @Test
  void lostPostAcknowledgmentIsRecoveredThroughRealRepeatedPostWithoutSecondExecution()
      throws Exception {
    var proxy =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    proxy.setExecutor(executor);
    var discardFirst = new java.util.concurrent.atomic.AtomicBoolean(true);
    var upstreamId = new java.util.concurrent.atomic.AtomicReference<String>();
    proxy.createContext(
        "/operations",
        request -> {
          try {
            byte[] body = request.getRequestBody().readAllBytes();
            HttpRequest.Builder upstream =
                HttpRequest.newBuilder(
                        URI.create(emulatorUrl + "/workers/worker-1" + request.getRequestURI()))
                    .timeout(Duration.ofSeconds(3));
            if (request.getRequestMethod().equals("POST")) {
              upstream
                  .header(
                      "Idempotency-Key", request.getRequestHeaders().getFirst("Idempotency-Key"))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            } else upstream.GET();
            HttpResponse<byte[]> response =
                HTTP.send(upstream.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (request.getRequestMethod().equals("POST")
                && discardFirst.compareAndSet(true, false)) {
              upstreamId.set(JSON.readTree(response.body()).path("id").asText());
              Thread.sleep(6000);
              request.close();
              return;
            }
            request.getResponseHeaders().set("Content-Type", "application/json");
            request.sendResponseHeaders(response.statusCode(), response.body().length);
            request.getResponseBody().write(response.body());
            request.close();
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            request.close();
          } catch (Exception error) {
            request.close();
          }
        });
    proxy.start();
    try {
      jdbc.update(
          "update workers set endpoint=? where id=1",
          "http://127.0.0.1:" + proxy.getAddress().getPort());
      TaskService.View task = send("Unknown-real-external-id", 1);
      String key = store.operation(task.operationId()).key;
      tickWhenDue();
      TaskService.View unknown = tasks.get(author, task.id());
      assertThat(unknown.exchange()).isEqualTo(Operation.Exchange.UNKNOWN);
      assertThat(unknown.externalId()).isNull();
      assertThat(upstreamId.get()).isNotBlank();
      command(operator(unknown), unknown, "reconcile", null);
      tickWhenDue();
      Operation recovered = store.operation(task.operationId());
      assertThat(recovered.externalId).isEqualTo(upstreamId.get());
      assertThat(recovered.key).isEqualTo(key);
      JsonNode inspection = awaitExternal(task.operationId());
      tickWhenDue();
      assertThat(tasks.get(author, task.id()).state()).isEqualTo(Task.State.AWAITING_ACCEPTANCE);
      assertThat(inspection.path("execution_count").asInt()).isEqualTo(1);
      assertThat(
              admin("GET", "/admin/operations/" + recovered.externalId, null)
                  .path("execution_count")
                  .asInt())
          .isEqualTo(1);
    } finally {
      proxy.stop(0);
      executor.shutdownNow();
    }
  }
}

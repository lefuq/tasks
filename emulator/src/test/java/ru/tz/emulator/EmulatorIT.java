package ru.tz.emulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.tz.emulator.application.*;
import ru.tz.emulator.config.Application;
import ru.tz.emulator.domain.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class EmulatorIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("emulator_test").withUsername("test").withPassword("test");
    static final ControlledClock clock = new ControlledClock();
    static final ObjectMapper json = new ObjectMapper();
    static final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    ConfigurableApplicationContext first;
    ConfigurableApplicationContext second;
    String firstUrl;
    String secondUrl;
    String body = "{\"name\":\"A\",\"description\":\"\",\"type\":\"process\"}";

    @Configuration(proxyBeanMethods = false)
    static class Boundaries {
        @Bean @Primary Clock controlledClock() { return clock; }
        @Bean @Primary ExecutionPolicy controlledPolicy() {
            return new ExecutionPolicy((origin, bound) -> origin == 10 ? 30 : 99);
        }
    }

    static class ControlledClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-14T00:00:00Z");
        volatile boolean real;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return real ? Instant.now() : now; }
    }

    @BeforeEach void startWorkers() {
        clock.real = false;
        clock.now = Instant.parse("2026-11-14T00:00:00Z");
        first = start("worker-1");
        first.getBean(JdbcTemplate.class).execute("truncate operations, worker_settings");
        second = start("worker-2");
        firstUrl = url(first);
        secondUrl = url(second);
    }

    @AfterEach void stopWorkers() {
        if (first != null) first.close();
        if (second != null) second.close();
    }

    ConfigurableApplicationContext start(String worker) {
        return start(worker, false);
    }

    ConfigurableApplicationContext start(String worker, boolean executionEnabled) {
        return new SpringApplicationBuilder(Application.class, Boundaries.class).run(
                "--server.port=0", "--spring.datasource.url=" + jdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(), "--emulator.worker=" + worker,
                "--emulator.execution-enabled=" + executionEnabled, "--logging.level.root=WARN",
                "--spring.main.banner-mode=off");
    }

    static String url(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    static String jdbcUrl() {
        var inspected = postgres.getDockerClient().inspectContainerCmd(postgres.getContainerId()).exec();
        String port = inspected.getNetworkSettings().getPorts().getBindings().get(new ExposedPort(5432))[0].getHostPortSpec();
        return "jdbc:postgresql://" + postgres.getHost() + ":" + port + "/" + postgres.getDatabaseName();
    }

    HttpResponse<String> request(String base, String method, String path, String key, String content) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
        if (key != null) request.header("Idempotency-Key", key);
        if (content != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, content == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(content)).build(), HttpResponse.BodyHandlers.ofString());
    }

    String accept(String base, String key) throws Exception {
        HttpResponse<String> response = request(base, "POST", "/operations", key, body);
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body()).get("id").asText();
    }

    JsonNode inspect(String id) throws Exception {
        HttpResponse<String> response = request(firstUrl, "GET", "/admin/operations/" + id, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    void configure(String base, Settings.Fault fault, Settings.Outcome outcome) throws Exception {
        String worker = base.equals(firstUrl) ? "worker-1" : "worker-2";
        assertThat(request(base, "PUT", "/admin/workers/" + worker, null,
                "{\"fault\":\"" + fault + "\",\"outcome\":\"" + outcome + "\"}").statusCode()).isEqualTo(200);
    }

    @Test void fullProcessLogsRetainFaultDiagnosticsWithoutSecrets(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String password = "qa-emulator-password-461";
        String session = "qa-emulator-session-462";
        String csrf = "qa-emulator-csrf-463";
        HttpRequest malformed = HttpRequest.newBuilder(URI.create(firstUrl + "/operations"))
                .header("Content-Type", "application/json")
                .header("Cookie", "SESSION=" + session)
                .header("X-CSRF-TOKEN", csrf)
                .header("Authorization", "Bearer " + password)
                .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"" + password + "\","))
                .build();
        assertThat(client.send(malformed, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        String id = accept(firstUrl, "log-faults");
        clock.now = clock.now.plusSeconds(30);
        first.getBean(Operations.class).tick();
        configure(firstUrl, Settings.Fault.unavailable, Settings.Outcome.random);
        assertThat(request(firstUrl, "GET", "/operations/" + id, null, null).statusCode()).isEqualTo(503);
        configure(firstUrl, Settings.Fault.invalid_json, Settings.Outcome.random);
        assertThat(request(firstUrl, "GET", "/operations/" + id, null, null).body()).isEqualTo("{");
        configure(firstUrl, Settings.Fault.lose_response, Settings.Outcome.random);
        assertThatThrownBy(() -> request(firstUrl, "GET", "/operations/" + id, null, null))
                .isInstanceOf(HttpTimeoutException.class);
        assertThat(output.getAll()).contains("Request rejected: malformed body", "Request rejected: status=503",
                        "Response fault applied: invalid_json", "Response fault applied: lose_response")
                .doesNotContain(password, session, csrf);
    }

    @Test void twentyConcurrentRequestsAcrossWorkersHaveOneExecutionAndConflictKeepsSnapshot() throws Exception {
        ExecutorService requests = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch release = new CountDownLatch(1);
        try {
            List<Future<String>> ids = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                String base = index % 2 == 0 ? firstUrl : secondUrl;
                ids.add(requests.submit(() -> {
                    ready.countDown();
                    release.await();
                    return accept(base, "concurrent");
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            Set<String> uniqueIds = new HashSet<>();
            for (Future<String> id : ids) uniqueIds.add(id.get(10, TimeUnit.SECONDS));
            assertThat(uniqueIds).hasSize(1);
            String id = uniqueIds.iterator().next();
            assertThat(request(secondUrl, "POST", "/operations", "concurrent", body.replace("\"A\"", "\"B\""))
                    .statusCode()).isEqualTo(409);
            assertThat(inspect(id).get("snapshot").get("name").asText()).isEqualTo("A");
            assertThat(inspect(id).get("execution_count").asInt()).isEqualTo(1);
            assertThat(first.getBean(JdbcTemplate.class).queryForObject("select count(*) from operations", Integer.class)).isEqualTo(1);
            clock.now = clock.now.plusSeconds(30);
            Future<Integer> one = requests.submit(() -> first.getBean(Operations.class).tick());
            Future<Integer> two = requests.submit(() -> second.getBean(Operations.class).tick());
            assertThat(one.get() + two.get()).isEqualTo(1);
            assertThat(inspect(id).get("execution_count").asInt()).isEqualTo(1);
        } finally { release.countDown(); requests.shutdownNow(); }
    }

    @Test void pendingUnknownRepeatAndRestartPreserveTimelineAndIncludeDowntime() throws Exception {
        assertThat(request(firstUrl, "GET", "/operations/" + UUID.randomUUID(), null, null).statusCode()).isEqualTo(404);
        String id = accept(firstUrl, "restart");
        assertThat(request(secondUrl, "GET", "/operations/" + id, null, null).statusCode()).isEqualTo(202);
        clock.now = clock.now.plusSeconds(20);
        assertThat(accept(secondUrl, "restart")).isEqualTo(id);
        assertThat(inspect(id).get("ready_at").asText()).isEqualTo("2026-11-14T00:00:30Z");
        first.close(); second.close();
        clock.now = clock.now.plusSeconds(20);
        first = start("worker-1"); firstUrl = url(first);
        assertThat(first.getBean(Operations.class).tick()).isEqualTo(1);
        JsonNode completed = inspect(id);
        assertThat(completed.get("result").get("processing_time").asInt()).isEqualTo(40);
        assertThat(completed.get("execution_count").asInt()).isEqualTo(1);
        assertThat(accept(firstUrl, "restart")).isEqualTo(id);
        first.close(); first = start("worker-1"); firstUrl = url(first);
        assertThat(inspect(id)).isEqualTo(completed);
        assertThat(first.getBean(Operations.class).tick()).isZero();
    }

    @Test void retryDoesNotRestartTimerAndBothBusinessOutcomesAreSaved() throws Exception {
        configure(firstUrl, Settings.Fault.normal, Settings.Outcome.fail);
        String failed = accept(firstUrl, "failed");
        configure(firstUrl, Settings.Fault.normal, Settings.Outcome.success);
        String successful = accept(firstUrl, "successful");
        clock.now = clock.now.plusSeconds(20);
        assertThat(accept(secondUrl, "failed")).isEqualTo(failed);
        assertThat(first.getBean(Operations.class).tick()).isZero();
        clock.now = clock.now.plusSeconds(10);
        assertThat(first.getBean(Operations.class).tick()).isEqualTo(2);
        JsonNode fail = json.readTree(request(secondUrl, "GET", "/operations/" + failed, null, null).body());
        assertThat(fail.get("status").asText()).isEqualTo("fail");
        assertThat(fail.get("data").asText()).isEmpty();
        JsonNode success = json.readTree(request(secondUrl, "GET", "/operations/" + successful, null, null).body());
        assertThat(success.get("status").asText()).isEqualTo("success");
        assertThat(success.get("processing_time").asInt()).isEqualTo(30);
        byte[] content = Base64.getDecoder().decode(success.get("data").asText());
        assertThat(content).hasSizeLessThanOrEqualTo(102400);
        assertThat(new String(content, java.nio.charset.StandardCharsets.UTF_8)).startsWith("Lorem ipsum");
        assertThat(accept(firstUrl, "new-key")).isNotEqualTo(successful);
    }

    @Test void faultsPersistAcrossRestartAndNeverCorruptIndependentInspection() throws Exception {
        String id = accept(firstUrl, "faults");
        clock.now = clock.now.plusSeconds(30);
        first.getBean(Operations.class).tick();
        JsonNode original = inspect(id);
        for (Settings.Fault fault : Settings.Fault.values()) {
            if (fault == Settings.Fault.lose_response) continue;
            configure(firstUrl, fault, Settings.Outcome.success);
            HttpResponse<String> response = request(firstUrl, "GET", "/operations/" + id, null, null);
            int expectedStatus = switch (fault) {
                case unavailable -> 503;
                case lost_result -> 404;
                default -> 200;
            };
            assertThat(response.statusCode()).isEqualTo(expectedStatus);
            assertThat(inspect(id)).isEqualTo(original);
        }
        configure(firstUrl, Settings.Fault.unavailable, Settings.Outcome.fail);
        first.close(); first = start("worker-1"); firstUrl = url(first);
        assertThat(request(firstUrl, "GET", "/operations/" + id, null, null).statusCode()).isEqualTo(503);
        assertThat(request(firstUrl, "POST", "/operations", "blocked", body).statusCode()).isEqualTo(503);
        assertThat(request(firstUrl, "DELETE", "/admin/workers/worker-1", null, null).statusCode()).isEqualTo(200);
        assertThat(request(firstUrl, "GET", "/operations/" + id, null, null).statusCode()).isEqualTo(200);
        assertThat(inspect(id)).isEqualTo(original);
    }

    @Test void savedResultSurvivesRealFiveSecondClientTimeoutAndRetryOnOtherWorker() throws Exception {
        String id = accept(firstUrl, "lost");
        clock.now = clock.now.plusSeconds(30);
        first.getBean(Operations.class).tick();
        JsonNode original = inspect(id);
        configure(firstUrl, Settings.Fault.lose_response, Settings.Outcome.random);
        assertThatThrownBy(() -> request(firstUrl, "GET", "/operations/" + id, null, null))
                .isInstanceOf(HttpTimeoutException.class);
        assertThat(inspect(id)).isEqualTo(original);
        assertThat(accept(secondUrl, "lost")).isEqualTo(id);
        assertThat(json.readTree(request(secondUrl, "GET", "/operations/" + id, null, null).body()))
                .isEqualTo(original.get("result"));
        configure(firstUrl, Settings.Fault.normal, Settings.Outcome.random);
        assertThat(request(firstUrl, "GET", "/operations/" + id, null, null).statusCode()).isEqualTo(200);
    }

    @Test void lostResultReturns404ForKnownIdAndSurvivesRestartWithoutLosingState() throws Exception {
        String id = accept(firstUrl, "lost-result");
        configure(firstUrl, Settings.Fault.lost_result, Settings.Outcome.random);
        assertThat(request(firstUrl, "GET", "/operations/" + id, null, null).statusCode()).isEqualTo(404);
        assertThat(inspect(id).get("state").asText()).isEqualTo("running");
        assertThat(request(secondUrl, "GET", "/operations/" + id, null, null).statusCode()).isEqualTo(202);
        clock.now = clock.now.plusSeconds(30);
        assertThat(first.getBean(Operations.class).tick()).isEqualTo(1);
        JsonNode original = inspect(id);
        assertThat(original.get("state").asText()).isEqualTo("completed");
        assertThat(original.get("execution_count").asInt()).isEqualTo(1);
        assertThat(request(firstUrl, "GET", "/workers/worker-1/operations/" + id, null, null).statusCode()).isEqualTo(404);
        assertThat(accept(firstUrl, "lost-result")).isEqualTo(id);
        assertThat(accept(secondUrl, "lost-result")).isEqualTo(id);
        assertThat(json.readTree(request(secondUrl, "GET", "/operations/" + id, null, null).body()))
                .isEqualTo(original.get("result"));
        first.close(); first = start("worker-1"); firstUrl = url(first);
        assertThat(request(firstUrl, "GET", "/operations/" + id, null, null).statusCode()).isEqualTo(404);
        assertThat(inspect(id)).isEqualTo(original);
        String replacement = accept(firstUrl, "replacement-result");
        assertThat(replacement).isNotEqualTo(id);
        assertThat(inspect(replacement).get("execution_count").asInt()).isEqualTo(1);
        assertThat(request(firstUrl, "DELETE", "/admin/workers/worker-1", null, null).statusCode()).isEqualTo(200);
        assertThat(json.readTree(request(firstUrl, "GET", "/operations/" + id, null, null).body()))
                .isEqualTo(original.get("result"));
        assertThat(inspect(id)).isEqualTo(original);
    }

    @Test void upgradesExistingWorkerSettingsWithoutRewritingInitialMigration() {
        org.flywaydb.core.Flyway.configure().dataSource(jdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("upgrade_test").target("1").load().migrate();
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                jdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update("insert into upgrade_test.worker_settings(worker, fault, outcome) values ('worker-1', 'normal', 'fail')");
        org.flywaydb.core.Flyway.configure().dataSource(jdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("upgrade_test").load().migrate();
        assertThat(jdbc.queryForObject("select outcome from upgrade_test.worker_settings where worker = 'worker-1'", String.class))
                .isEqualTo("fail");
        assertThat(jdbc.update("update upgrade_test.worker_settings set fault = 'lost_result' where worker = 'worker-1'"))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update upgrade_test.worker_settings set fault = 'invalid' where worker = 'worker-1'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void malformedOrMissingFieldsNeverReachRegistryAndConstraintsRejectInvalidState() throws Exception {
        assertThat(request(firstUrl, "POST", "/operations", "bad", "{").statusCode()).isEqualTo(400);
        for (String field : List.of("name", "description", "type")) {
            var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(body);
            invalid.remove(field);
            assertThat(request(firstUrl, "POST", "/operations", "missing-" + field, invalid.toString()).statusCode()).isEqualTo(422);
        }
        JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject("select count(*) from operations", Integer.class)).isZero();
        String id = accept(firstUrl, "constraint");
        assertThatThrownBy(() -> jdbc.update("update operations set execution_count = 2 where id = ?", UUID.fromString(id)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update operations set ready_at = started_at where id = ?", UUID.fromString(id)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void realExecutionWaitsWhilePostAndRepeatsRemainFast() throws Exception {
        clock.real = true;
        first.close(); first = start("worker-1", true); firstUrl = url(first);
        long start = System.nanoTime();
        String id = accept(firstUrl, "real");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(request(firstUrl, "GET", "/operations/" + id, null, null).statusCode()).isEqualTo(202);
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        boolean repeated = false;
        while (System.nanoTime() < deadline) {
            if (!repeated && Duration.ofNanos(System.nanoTime() - start).toSeconds() >= 10) {
                assertThat(accept(secondUrl, "real")).isEqualTo(id);
                repeated = true;
            }
            if (inspect(id).get("state").asText().equals("completed")) break;
            Thread.sleep(100);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(elapsed).isBetween(Duration.ofSeconds(10), Duration.ofSeconds(60));
        JsonNode result = inspect(id);
        assertThat(result.get("state").asText()).isEqualTo("completed");
        assertThat(result.get("result").get("processing_time").asLong()).isBetween(30L, 31L);
        assertThat(result.get("execution_count").asInt()).isEqualTo(1);
    }

    @Test void postgresRestartPreservesResultsAndConfiguration() throws Exception {
        String id = accept(firstUrl, "database-restart");
        clock.now = clock.now.plusSeconds(30);
        first.getBean(Operations.class).tick();
        JsonNode original = inspect(id);
        configure(firstUrl, Settings.Fault.unavailable, Settings.Outcome.fail);
        first.close(); second.close();
        postgres.getDockerClient().restartContainerCmd(postgres.getContainerId()).exec();
        boolean databaseReady = false;
        for (int attempt = 0; attempt < 100; attempt++) {
            if (postgres.execInContainer("pg_isready", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName())
                    .getExitCode() == 0) {
                databaseReady = true;
                break;
            }
            Thread.sleep(100);
        }
        assertThat(databaseReady).isTrue();
        first = start("worker-1"); firstUrl = url(first);
        assertThat(inspect(id)).isEqualTo(original);
        assertThat(request(firstUrl, "GET", "/operations/" + id, null, null).statusCode()).isEqualTo(503);
        configure(firstUrl, Settings.Fault.normal, Settings.Outcome.random);
        assertThat(accept(firstUrl, "database-restart")).isEqualTo(id);
        assertThat(first.getBean(Operations.class).tick()).isZero();
    }
}

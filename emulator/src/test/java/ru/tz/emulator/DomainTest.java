package ru.tz.emulator;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.tz.emulator.application.ExecutionPolicy;
import ru.tz.emulator.domain.*;
import static org.assertj.core.api.Assertions.*;

class DomainTest {
    @Test void validatesAndNormalizesSubmission() {
        assertThat(new Submission(" Ёж-1_. ", "", "process").name()).isEqualTo("Ёж-1_.");
        assertThat(new Submission("A".repeat(100), "x".repeat(1000), "research").type()).isEqualTo("research");
        assertThat(new Submission("B", "", "collect").type()).isEqualTo("collect");
        assertThatThrownBy(() -> new Submission(null, "", "process"))
                .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.status()).isEqualTo(422));
        assertThatThrownBy(() -> new Submission("A", null, "process")).isInstanceOf(Problem.class);
        assertThatThrownBy(() -> new Submission("A", "", null)).isInstanceOf(Problem.class);
        assertThatThrownBy(() -> new Submission("A", "x".repeat(1001), "process")).isInstanceOf(Problem.class);
        assertThatThrownBy(() -> new Submission("A", "", "other")).isInstanceOf(Problem.class);
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "A B", "A[", "A\\", "A`", "A@", "🙂"})
    void rejectsInvalidNames(String name) {
        assertThatThrownBy(() -> new Submission(name, "", "process"))
                .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.status()).isEqualTo(500));
    }

    @Test void rejectsLongName() {
        assertThatThrownBy(() -> new Submission("A".repeat(101), "", "process")).isInstanceOf(Problem.class);
    }

    @Test void completesOnlyAtDeadlineAndKeepsOriginalResult() {
        Operation operation = operation(Settings.Outcome.success);
        assertThat(operation.result()).isNull();
        assertThat(operation.complete(operation.readyAt().minusNanos(1), "data")).isSameAs(operation);
        Operation completed = operation.complete(operation.readyAt(), "data");
        assertThat(completed.result().status()).isEqualTo("success");
        assertThat(completed.result().data()).isEqualTo("data");
        assertThat(completed.complete(operation.readyAt().plusSeconds(30), "changed")).isSameAs(completed);
    }

    @Test void truncatesEachTimestampBeforeSubtractionAndIncludesDowntime() {
        Operation completed = operation(Settings.Outcome.success).complete(Instant.parse("2026-11-14T00:00:11.100Z"), "data");
        assertThat(completed.result().processing_time()).isEqualTo(11);
        assertThat(operation(Settings.Outcome.success).complete(Instant.parse("2026-11-14T00:00:40Z"), "data")
                .result().processing_time()).isEqualTo(40);
        assertThat(completed.result().id()).isEqualTo(completed.id());
    }

    @Test void failAlwaysHasEmptyData() {
        Operation operation = operation(Settings.Outcome.fail);
        assertThat(operation.complete(operation.readyAt(), "ignored").result().data()).isEmpty();
    }

    @Test void randomnessHasCorrectBoundsAndTwentyPercentFail() {
        ExecutionPolicy minimum = new ExecutionPolicy((origin, bound) -> origin);
        ExecutionPolicy maximum = new ExecutionPolicy((origin, bound) -> bound - 1);
        assertThat(minimum.seconds()).isEqualTo(10);
        assertThat(maximum.seconds()).isEqualTo(60);
        for (int draw = 0; draw < 100; draw++) {
            int sample = draw;
            ExecutionPolicy policy = new ExecutionPolicy((origin, bound) -> sample);
            assertThat(policy.outcome(Settings.Outcome.random))
                    .isEqualTo(draw < 20 ? Settings.Outcome.fail : Settings.Outcome.success);
        }
        assertThat(minimum.outcome(Settings.Outcome.success)).isEqualTo(Settings.Outcome.success);
        assertThat(maximum.outcome(Settings.Outcome.fail)).isEqualTo(Settings.Outcome.fail);
        byte[] content = Base64.getDecoder().decode(minimum.content());
        assertThat(content).hasSizeLessThanOrEqualTo(102400);
        assertThat(new String(content, java.nio.charset.StandardCharsets.UTF_8)).startsWith("Lorem ipsum");
        assertThatThrownBy(() -> new Settings(null, Settings.Outcome.random)).isInstanceOf(Problem.class);
        assertThatThrownBy(() -> new Settings(Settings.Fault.normal, null)).isInstanceOf(Problem.class);
    }

    static Operation operation(Settings.Outcome outcome) {
        Instant start = Instant.parse("2026-11-14T00:00:00.900Z");
        return new Operation(UUID.randomUUID(), "key", new Submission("A", "", "process"), "worker-1",
                start, start.plusSeconds(10), null, outcome, 1, null);
    }
}

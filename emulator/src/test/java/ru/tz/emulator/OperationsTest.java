package ru.tz.emulator;

import java.time.*;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.tz.emulator.application.*;
import ru.tz.emulator.domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationsTest {
    Registry registry;
    Clock clock;
    ExecutionPolicy policy;
    Operations operations;
    Submission submission = new Submission("A", "", "process");

    @BeforeEach void setup() {
        registry = mock(Registry.class);
        clock = Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC);
        policy = new ExecutionPolicy((origin, bound) -> origin);
        operations = new Operations(registry, clock, policy);
        when(registry.settings(anyString())).thenReturn(Settings.NORMAL);
        when(registry.register(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void acceptsWithDurableTimingAndOutcome() {
        Operation operation = operations.accept("worker-2", "key", submission);
        assertThat(operation.worker()).isEqualTo("worker-2");
        assertThat(operation.key()).isEqualTo("key");
        assertThat(operation.startedAt()).isEqualTo(clock.instant());
        assertThat(operation.readyAt()).isEqualTo(clock.instant().plusSeconds(10));
        assertThat(operation.executionCount()).isEqualTo(1);
        assertThat(operation.outcome()).isEqualTo(Settings.Outcome.fail);
    }

    @Test void repeatKeepsIdTimerResultAndCountAcrossEndpoints() {
        Operation original = DomainTest.operation(Settings.Outcome.success);
        when(registry.register(any())).thenReturn(original);
        assertThat(operations.accept("worker-2", "key", submission)).isSameAs(original);
    }

    @Test void conflictDoesNotChangeOriginalSnapshot() {
        when(registry.register(any())).thenReturn(DomainTest.operation(Settings.Outcome.success));
        assertThatThrownBy(() -> operations.accept("worker-2", "key", new Submission("B", "", "process")))
                .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.status()).isEqualTo(409));
        verify(registry, never()).completeDue(any(), any());
    }

    @Test void unavailableBlocksPublicRequestsButAllowsInspectionAndReset() {
        when(registry.settings("worker-1")).thenReturn(new Settings(Settings.Fault.unavailable, Settings.Outcome.random));
        assertThatThrownBy(() -> operations.accept("worker-1", "key", submission))
                .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.status()).isEqualTo(503));
        assertThatThrownBy(() -> operations.get("worker-1", UUID.randomUUID())).isInstanceOf(Problem.class);
        Operation original = DomainTest.operation(Settings.Outcome.success);
        when(registry.operation(original.id())).thenReturn(original);
        assertThat(operations.inspect(original.id())).isSameAs(original);
        operations.configure("worker-1", Settings.NORMAL);
        verify(registry).settings("worker-1", Settings.NORMAL);
        verify(registry, never()).register(any());
    }

    @Test void missingKeyAndUnknownOperation() {
        for (String key : new String[]{null, "", " "}) {
            assertThatThrownBy(() -> operations.accept("worker-1", key, submission))
                    .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.status()).isEqualTo(422));
        }
        assertThatThrownBy(() -> operations.inspect(UUID.randomUUID()))
                .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.status()).isEqualTo(404));
    }

    @Test void lostResultHidesPublicGetButPreservesInspectionAndIdempotentPost() {
        when(registry.settings("worker-1")).thenReturn(new Settings(Settings.Fault.lost_result, Settings.Outcome.random));
        Operation running = DomainTest.operation(Settings.Outcome.success);
        for (Operation original : new Operation[]{running, running.complete(running.readyAt(), policy.content())}) {
            when(registry.operation(original.id())).thenReturn(original);
            when(registry.register(any())).thenReturn(original);
            assertThatThrownBy(() -> operations.get("worker-1", original.id()))
                    .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.status()).isEqualTo(404));
            assertThat(operations.inspect(original.id())).isSameAs(original);
            assertThat(operations.get("worker-2", original.id())).isSameAs(original);
            assertThat(operations.accept("worker-1", original.key(), original.submission())).isSameAs(original);
        }
        when(registry.settings("worker-1")).thenReturn(Settings.NORMAL);
        assertThat(operations.get("worker-1", running.id()).result().data()).isEqualTo(policy.content());
        verify(registry, never()).completeDue(any(), any());
    }

    @Test void forcedOutcomeIsIndependentOfType() {
        when(registry.settings("worker-1")).thenReturn(new Settings(Settings.Fault.normal, Settings.Outcome.success));
        for (String type : new String[]{"process", "research", "collect"}) {
            assertThat(operations.accept("worker-1", UUID.randomUUID().toString(), new Submission("A", "", type)).outcome())
                    .isEqualTo(Settings.Outcome.success);
        }
    }

    @Test void completionUsesClockAndEmptiesFailData() {
        Clock later = Clock.fixed(clock.instant().plusSeconds(40), ZoneOffset.UTC);
        Operations resumed = new Operations(registry, later, policy);
        when(registry.completeDue(eq(later.instant()), any())).thenAnswer(call -> {
            Function<Operation, Operation> complete = call.getArgument(1);
            for (Settings.Outcome outcome : new Settings.Outcome[]{Settings.Outcome.success, Settings.Outcome.fail}) {
                Operation completed = complete.apply(DomainTest.operation(outcome));
                assertThat(completed.finishedAt()).isEqualTo(later.instant());
                assertThat(completed.data()).isEqualTo(outcome == Settings.Outcome.fail ? "" : policy.content());
            }
            return 2;
        });
        assertThat(resumed.tick()).isEqualTo(2);
    }
}

package ru.tz.exchange;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.tz.exchange.application.*;
import ru.tz.exchange.domain.*;

class ExchangeServiceTest {
  Ports.Store store;
  Ports.External external;
  ExchangeService exchange;
  Task task;
  Operation operation;
  final Instant now = Instant.parse("2026-11-14T00:00:00Z");
  Ports.Transactions transactions =
      new Ports.Transactions() {
        public <T> T run(Supplier<T> action) {
          return action.get();
        }
      };

  @BeforeEach
  void setup() {
    store = mock(Ports.Store.class);
    external = mock(Ports.External.class);
    Ports.Encoding encoding = mock(Ports.Encoding.class);
    when(encoding.encode(any())).thenReturn("{}");
    exchange =
        new ExchangeService(
            store,
            transactions,
            () -> now,
            UUID::randomUUID,
            external,
            encoding,
            new RetryPolicy(() -> 2));
    task = Task.create(new Actor(1, 1, Actor.Role.INITIATOR, "a"), "Task", "", Task.Type.process);
    task.id = 1;
    task.operatorId = 2L;
    task.workerId = 1L;
    task.state = Task.State.EXECUTING;
    task.activeOperationId = UUID.randomUUID();
    operation = Operation.start(task, task.activeOperationId, "permanent-key", now);
    when(store.operation(operation.id)).thenReturn(operation);
    when(store.task(1, true)).thenReturn(task);
    when(store.owns(any(), any())).thenReturn(true);
  }

  Ports.Job job(int failures, String kind) {
    return new Ports.Job(
        UUID.randomUUID(), operation.id, kind, failures, now, UUID.randomUUID(), now);
  }

  @Test
  void stopsAfterSixTemporaryFailuresAndPreservesKey() {
    int[] delays = {7, 17, 32, 62, 122};
    for (int index = 0; index < 6; index++) {
      Ports.Job job = job(index, "POST");
      exchange.apply(job, new Ports.Reply(503, "unavailable", false), true);
      if (index < 5)
        verify(store).reschedule(job, "POST", index + 1, now.plusSeconds(delays[index]));
      else verify(store).finish(job);
    }
    assertThat(operation.problem).isEqualTo(Operation.Problem.EXHAUSTED);
    assertThat(operation.key).isEqualTo("permanent-key");
  }

  @Test
  void processingDoesNotSpendErrorBudget() {
    operation.externalId = "x";
    for (int index = 0; index < 10; index++) {
      Ports.Job job = job(0, "GET");
      exchange.apply(job, new Ports.Reply(202, "", false), false);
      verify(store).reschedule(job, "GET", 0, now.plusSeconds(10));
    }
    Ports.Job failure = job(0, "GET");
    exchange.apply(failure, new Ports.Reply(503, "", false), false);
    verify(store).reschedule(failure, "GET", 1, now.plusSeconds(7));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 422, 409})
  void permanentErrorsNeverRetry(int status) {
    Ports.Job job = job(0, "POST");
    exchange.apply(job, new Ports.Reply(status, "bad", false), true);
    verify(store).finish(job);
    verify(store, never()).reschedule(any(), anyString(), anyInt(), any());
    assertThat(operation.problem).isEqualTo(Operation.Problem.CONTRACT);
  }

  @Test
  void unknownDeliverySchedulesAutomaticReconciliation() {
    Ports.Job job = job(0, "POST");
    exchange.apply(job, new Ports.Reply(0, "timeout", true), true);
    verify(store).reschedule(job, "AUTO_RECONCILE", 1, now.plusSeconds(7));
    assertThat(operation.exchange).isEqualTo(Operation.Exchange.UNKNOWN);
  }

  @Test
  void knownId404RequiresNewOperation() {
    operation.externalId = "lost";
    Ports.Job job = job(0, "GET");
    exchange.apply(job, new Ports.Reply(404, "", false), false);
    assertThat(operation.problem).isEqualTo(Operation.Problem.LOST_RESULT);
    verify(store).finish(job);
  }

  @Test
  void staleOperationDoesNotReplaceCurrentState() {
    task.activeOperationId = UUID.randomUUID();
    Ports.Job job = job(0, "GET");
    exchange.apply(job, new Ports.Reply(200, "old", false), false);
    verify(external, never()).result(anyString());
    verify(store, never()).save(any(Task.class));
    verify(store).finish(job);
  }

  @Test
  void expiredLeaseCannotApplyAResponse() {
    when(store.owns(any(), any())).thenReturn(false);
    exchange.apply(job(0, "GET"), new Ports.Reply(200, "x", false), false);
    verify(store, never()).attempt(any(), any(), any());
    verify(store, never()).save(any(Task.class));
  }

  @Test
  void duplicateHasOneBusinessEffect() {
    operation.externalId = "x";
    when(external.result("response")).thenReturn(new ExternalResult("x", "success", 10, "QQ=="));
    exchange.apply(job(0, "GET"), new Ports.Reply(200, "response", false), false);
    long version = task.version;
    exchange.apply(job(0, "GET"), new Ports.Reply(200, "response", false), false);
    assertThat(task.version).isEqualTo(version);
    assertThat(task.state).isEqualTo(Task.State.AWAITING_ACCEPTANCE);
  }

  @Test
  void completedReconciliationPreservesAcceptedResult() {
    operation.externalId = "x";
    operation.result = new ExternalResult("x", "success", 10, "QQ==");
    task.state = Task.State.COMPLETED;
    ExternalResult other = new ExternalResult("x", "fail", 12, "");
    when(external.result("other")).thenReturn(other);
    exchange.apply(job(0, "RECONCILE"), new Ports.Reply(200, "other", false), false);
    assertThat(operation.result.status()).isEqualTo("success");
    assertThat(operation.confirmedResult).isNull();
    assertThat(task.state).isEqualTo(Task.State.COMPLETED);
    assertThat(task.version).isZero();
    verify(store).reconcile(eq(operation), eq(now), anyString(), eq("other"));
  }

  @Test
  void automaticMismatchRequiresManualConfirmation() {
    operation.externalId = "x";
    operation.result = new ExternalResult("x", "success", 10, "QQ==");
    task.state = Task.State.AWAITING_ACCEPTANCE;
    when(external.result("other")).thenReturn(new ExternalResult("x", "fail", 11, ""));
    exchange.apply(job(0, "AUTO_RECONCILE"), new Ports.Reply(200, "other", false), false);
    assertThat(operation.problem).isEqualTo(Operation.Problem.MISMATCH);
    assertThat(operation.acceptableResult()).isNull();
  }

  @Test
  void invalidPostAcknowledgmentRequiresReviewWithoutReturnToAuthor() {
    when(external.acceptedId("bad")).thenThrow(new Failure("InvalidResult"));
    Ports.Job job = job(0, "POST");
    exchange.apply(job, new Ports.Reply(202, "bad", false), true);
    assertThat(operation.problem).isEqualTo(Operation.Problem.CONTRACT);
    Actor operator = new Actor(2, 1, Actor.Role.OPERATOR, "Operator");
    assertThat(task.actions(operator, operation))
        .contains("retry", "reconcile")
        .doesNotContain("return");
    verify(store).finish(job);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"RECONCILE", "AUTO_RECONCILE"})
  void matchingCompletedReconciliationClosesProblemAndPreservesAcceptedResult(String kind) {
    operation.externalId = "x";
    ExternalResult accepted = new ExternalResult("x", "success", 10, "QQ==");
    operation.result = accepted;
    operation.problem = Operation.Problem.MISMATCH;
    operation.conflictingResults = true;
    operation.confirmedResult = new ExternalResult("x", "fail", 12, "");
    task.state = Task.State.COMPLETED;
    when(external.result("matches")).thenReturn(accepted);
    exchange.apply(job(0, kind), new Ports.Reply(200, "matches", false), false);
    assertThat(operation.result).isSameAs(accepted);
    assertThat(operation.problem).isNull();
    assertThat(operation.conflictingResults).isFalse();
    assertThat(operation.confirmedResult).isNull();
    assertThat(task.state).isEqualTo(Task.State.COMPLETED);
    assertThat(task.version).isZero();
    verify(store).resolve(operation.id, 0, now, "recovered");
    verify(store, never()).problem(any(), any(), anyString(), anyString());
  }
}

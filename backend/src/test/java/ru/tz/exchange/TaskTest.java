package ru.tz.exchange;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.tz.exchange.domain.*;

class TaskTest {
  private final Actor author = new Actor(1, 1, Actor.Role.INITIATOR, "Author");
  private final Actor operator = new Actor(2, 1, Actor.Role.OPERATOR, "Operator");

  private Task waiting() {
    Task task = Task.create(author, "  Ёж-1_А.txt  ", "", Task.Type.process);
    task.id = 10;
    task.assign(operator.id());
    return task;
  }

  @Test
  void trimsNameAndAssignsWithoutSending() {
    Task task = waiting();
    assertThat(task.name).isEqualTo("Ёж-1_А.txt");
    assertThat(task.state).isEqualTo(Task.State.WAITING_TO_SEND);
    assertThat(task.activeOperationId).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "A B", "A/B", "A[B", "A`B", "😀"})
  void rejectsInvalidName(String name) {
    assertThatThrownBy(() -> Task.create(author, name, "", Task.Type.collect))
        .isInstanceOf(Failure.class);
  }

  @Test
  void validatesBoundaries() {
    for (int size : new int[] {1, 100})
      assertThat(Task.create(author, "A".repeat(size), "x".repeat(1000), Task.Type.research).name)
          .hasSize(size);
    assertThatThrownBy(() -> Task.create(author, "A".repeat(101), "", Task.Type.process))
        .isInstanceOf(Failure.class);
    assertThatThrownBy(() -> Task.create(author, "A", "x".repeat(1001), Task.Type.process))
        .isInstanceOf(Failure.class);
    assertThatThrownBy(() -> Task.create(author, "A", "", null)).isInstanceOf(Failure.class);
  }

  @Test
  void accessEnforcesServiceAuthorAndAssignment() {
    Task task = waiting();
    assertThatThrownBy(() -> task.readableBy(new Actor(1, 2, Actor.Role.OPERATOR, "Other")))
        .isInstanceOf(Failure.class);
    assertThatThrownBy(() -> task.readableBy(new Actor(3, 1, Actor.Role.INITIATOR, "Other")))
        .isInstanceOf(Failure.class);
    assertThatThrownBy(() -> task.operator(new Actor(3, 1, Actor.Role.OPERATOR, "Other")))
        .isInstanceOf(Failure.class);
    task.readableBy(new Actor(3, 1, Actor.Role.OPERATOR, "Other"));
    assertThat(task.actions(new Actor(3, 1, Actor.Role.OPERATOR, "Other"), null)).isEmpty();
  }

  @Test
  void returnAndSaveWithoutChangesKeepsOperatorAndUsesNewOperation() {
    Task task = waiting();
    task.chooseWorker(operator, 1, null);
    UUID first = UUID.randomUUID();
    task.send(operator, first, null);
    Operation operation = Operation.start(task, first, "old-key", Instant.EPOCH);
    operation.problem = Operation.Problem.INVALID_RESULT;
    task.returnToAuthor(operator, operation);
    task.edit(author, task.description, task.type);
    assertThat(task.operatorId).isEqualTo(operator.id());
    assertThat(task.state).isEqualTo(Task.State.WAITING_TO_SEND);
    UUID next = UUID.randomUUID();
    task.send(operator, next, operation);
    assertThat(task.activeOperationId).isEqualTo(next).isNotEqualTo(first);
  }

  @Test
  void failIsAcceptableAndCompletionIsImmutable() {
    Task task = waiting();
    task.chooseWorker(operator, 1, null);
    UUID id = UUID.randomUUID();
    task.send(operator, id, null);
    Operation operation = Operation.start(task, id, "key", Instant.EPOCH);
    operation.externalId = "external";
    operation.observe(new ExternalResult("external", "fail", 10, ""), false);
    task.received();
    assertThatThrownBy(() -> task.returnToAuthor(operator, operation)).isInstanceOf(Failure.class);
    task.accept(operator, operation);
    assertThat(task.state).isEqualTo(Task.State.COMPLETED);
    assertThat(task.actions(operator, operation)).containsExactly("reconcile");
    assertThatThrownBy(() -> task.edit(author, "new", Task.Type.collect))
        .isInstanceOf(Failure.class);
    assertThatThrownBy(() -> task.send(operator, UUID.randomUUID(), operation))
        .isInstanceOf(Failure.class);
    assertThatThrownBy(() -> task.accept(operator, operation)).isInstanceOf(Failure.class);
  }

  @Test
  void workerChangeRequiresTemporaryErrorOrLostResult() {
    Task task = waiting();
    task.chooseWorker(operator, 1, null);
    task.send(operator, UUID.randomUUID(), null);
    Operation operation = Operation.start(task, task.activeOperationId, "key", Instant.EPOCH);
    assertThatThrownBy(() -> task.chooseWorker(operator, 2, operation)).isInstanceOf(Failure.class);
    operation.temporaryFailure = true;
    task.chooseWorker(operator, 2, operation);
    assertThat(task.workerId).isEqualTo(2L);
    assertThatThrownBy(() -> task.expectedVersion(-1)).isInstanceOf(Failure.class);
  }

  @Test
  void lostResultAllowsNewSendButNotRetry() {
    Task task = waiting();
    task.chooseWorker(operator, 1, null);
    task.send(operator, UUID.randomUUID(), null);
    Operation operation = Operation.start(task, task.activeOperationId, "old", Instant.EPOCH);
    operation.problem = Operation.Problem.LOST_RESULT;
    assertThat(task.actions(operator, operation))
        .contains("send", "worker")
        .doesNotContain("retry");
    assertThatThrownBy(operation::restart).isInstanceOf(Failure.class);
  }

  @Test
  void invalidReconciliationBeforeAcceptanceAllowsReturnRetryAndReconciliation() {
    Task task = waiting();
    task.chooseWorker(operator, 1, null);
    task.send(operator, UUID.randomUUID(), null);
    Operation operation = Operation.start(task, task.activeOperationId, "key", Instant.EPOCH);
    operation.externalId = "x";
    operation.observe(new ExternalResult("x", "success", 10, "QQ=="), false);
    task.received();
    operation.problem = Operation.Problem.INVALID_RESULT;
    assertThat(task.actions(operator, operation))
        .contains("return", "retry", "reconcile")
        .doesNotContain("accept");
    operation.restart();
    assertThat(operation.acceptableResult()).isNull();
    task.returnToAuthor(operator, operation);
    assertThat(task.state).isEqualTo(Task.State.RETURNED);
    assertThat(operation.result.status()).isEqualTo("success");
  }

  @Test
  void lostResultBeforeAcceptanceCanBeResentWithNewOperation() {
    Task task = waiting();
    task.chooseWorker(operator, 1, null);
    task.send(operator, UUID.randomUUID(), null);
    Operation previous = Operation.start(task, task.activeOperationId, "key", Instant.EPOCH);
    previous.externalId = "x";
    previous.observe(new ExternalResult("x", "success", 10, "QQ=="), false);
    task.received();
    previous.problem = Operation.Problem.LOST_RESULT;
    assertThat(task.actions(operator, previous))
        .contains("send", "worker")
        .doesNotContain("accept");
    task.chooseWorker(operator, 2, previous);
    UUID replacement = UUID.randomUUID();
    task.send(operator, replacement, previous);
    assertThat(task.state).isEqualTo(Task.State.EXECUTING);
    assertThat(task.activeOperationId).isEqualTo(replacement);
  }

  @Test
  void versionChangesOnlyWithStateOrEditing() {
    Task task = waiting();
    long assignedVersion = task.version;
    task.chooseWorker(operator, 1, null);
    assertThat(task.version).isEqualTo(assignedVersion);
    task.send(operator, UUID.randomUUID(), null);
    assertThat(task.version).isEqualTo(assignedVersion + 1);
    Operation previous = Operation.start(task, task.activeOperationId, "key", Instant.EPOCH);
    previous.problem = Operation.Problem.LOST_RESULT;
    task.chooseWorker(operator, 2, previous);
    task.send(operator, UUID.randomUUID(), previous);
    assertThat(task.version).isEqualTo(assignedVersion + 1);
    task.received();
    assertThat(task.version).isEqualTo(assignedVersion + 2);
    task.received();
    assertThat(task.version).isEqualTo(assignedVersion + 2);
  }
}

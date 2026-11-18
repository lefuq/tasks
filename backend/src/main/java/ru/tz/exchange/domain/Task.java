package ru.tz.exchange.domain;

import static ru.tz.exchange.domain.Failure.require;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Task {
  public enum State {
    NEW,
    WAITING_TO_SEND,
    EXECUTING,
    AWAITING_ACCEPTANCE,
    RETURNED,
    COMPLETED
  }

  public enum Type {
    process,
    research,
    collect
  }

  public long id;
  public long serviceId;
  public long authorId;
  public Long operatorId;
  public Long workerId;
  public UUID activeOperationId;
  public String name;
  public String description;
  public Type type;
  public State state;
  public long version;

  public Task() {}

  public static Task create(Actor actor, String name, String description, Type type) {
    require(actor.role() == Actor.Role.INITIATOR, "Forbidden");
    require(name != null, "Validation");
    String normalized = name.strip();
    require(normalized.matches("[A-Za-zА-Яа-яЁё0-9._-]{1,100}"), "Validation");
    validateFields(description, type);
    Task task = new Task();
    task.serviceId = actor.serviceId();
    task.authorId = actor.id();
    task.name = normalized;
    task.description = description;
    task.type = type;
    task.state = State.NEW;
    return task;
  }

  public static void validateFields(String description, Type type) {
    require(
        description != null
            && description.codePointCount(0, description.length()) <= 1000
            && type != null,
        "Validation");
  }

  public void readableBy(Actor actor) {
    require(serviceId == actor.serviceId(), "Forbidden");
    require(actor.role() == Actor.Role.OPERATOR || authorId == actor.id(), "Forbidden");
  }

  public void operator(Actor actor) {
    readableBy(actor);
    require(
        actor.role() == Actor.Role.OPERATOR && operatorId != null && operatorId == actor.id(),
        "Forbidden");
  }

  public void expectedVersion(long expected) {
    require(expected == version, "VersionConflict");
  }

  public void assign(long operator) {
    require(state == State.NEW, "InvalidState");
    operatorId = operator;
    state = State.WAITING_TO_SEND;
    version++;
  }

  public void edit(Actor actor, String description, Type type) {
    readableBy(actor);
    require(actor.role() == Actor.Role.INITIATOR && authorId == actor.id(), "Forbidden");
    require(
        state == State.NEW || state == State.WAITING_TO_SEND || state == State.RETURNED,
        "InvalidState");
    validateFields(description, type);
    this.description = description;
    this.type = type;
    if (state == State.RETURNED) state = State.WAITING_TO_SEND;
    version++;
  }

  public boolean canChooseWorker(Operation operation) {
    return state == State.WAITING_TO_SEND
        || (state == State.EXECUTING || state == State.AWAITING_ACCEPTANCE)
            && operation != null
            && (operation.temporaryFailure || operation.problem == Operation.Problem.LOST_RESULT);
  }

  public void chooseWorker(Actor actor, long worker, Operation operation) {
    operator(actor);
    require(canChooseWorker(operation), "InvalidState");
    workerId = worker;
  }

  public void send(Actor actor, UUID operationId, Operation previous) {
    operator(actor);
    require(
        state == State.WAITING_TO_SEND
            || (state == State.EXECUTING || state == State.AWAITING_ACCEPTANCE)
                && previous != null
                && previous.problem == Operation.Problem.LOST_RESULT,
        "InvalidState");
    require(workerId != null, "WorkerRequired");
    activeOperationId = operationId;
    if (state != State.EXECUTING) {
      state = State.EXECUTING;
      version++;
    }
  }

  public void returnToAuthor(Actor actor, Operation operation) {
    operator(actor);
    require(
        (state == State.EXECUTING || state == State.AWAITING_ACCEPTANCE)
            && operation != null
            && operation.problem == Operation.Problem.INVALID_RESULT,
        "InvalidState");
    state = State.RETURNED;
    version++;
  }

  public void received() {
    require(state == State.EXECUTING || state == State.AWAITING_ACCEPTANCE, "InvalidState");
    if (state != State.AWAITING_ACCEPTANCE) {
      state = State.AWAITING_ACCEPTANCE;
      version++;
    }
  }

  public void accept(Actor actor, Operation operation) {
    operator(actor);
    require(
        state == State.AWAITING_ACCEPTANCE
            && operation != null
            && operation.acceptableResult() != null,
        "InvalidState");
    state = State.COMPLETED;
    version++;
  }

  public List<String> actions(Actor actor, Operation operation) {
    readableBy(actor);
    List<String> actions = new ArrayList<>();
    if (actor.role() == Actor.Role.INITIATOR
        && (state == State.NEW || state == State.WAITING_TO_SEND || state == State.RETURNED))
      actions.add("edit");
    if (actor.role() != Actor.Role.OPERATOR || operatorId == null || operatorId != actor.id())
      return actions;
    if (canChooseWorker(operation)) actions.add("worker");
    if (workerId != null
        && (state == State.WAITING_TO_SEND
            || (state == State.EXECUTING || state == State.AWAITING_ACCEPTANCE)
                && operation != null
                && operation.problem == Operation.Problem.LOST_RESULT)) actions.add("send");
    if (operation != null) {
      actions.add("reconcile");
      if (state != State.COMPLETED
          && state != State.RETURNED
          && state != State.WAITING_TO_SEND
          && operation.problem != null
          && operation.problem != Operation.Problem.LOST_RESULT) actions.add("retry");
      if ((state == State.EXECUTING || state == State.AWAITING_ACCEPTANCE)
          && operation.problem == Operation.Problem.INVALID_RESULT) actions.add("return");
      if (state == State.AWAITING_ACCEPTANCE && operation.acceptableResult() != null)
        actions.add("accept");
    }
    return actions;
  }
}

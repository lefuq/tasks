package ru.tz.exchange.domain;

import static ru.tz.exchange.domain.Failure.require;

import java.time.Instant;
import java.util.UUID;

public final class Operation {
  public enum Exchange {
    PENDING,
    DELIVERING,
    ACKNOWLEDGED,
    UNKNOWN,
    RETRY_WAIT,
    NEEDS_REVIEW
  }

  public enum Problem {
    TEMPORARY,
    EXHAUSTED,
    INVALID_RESULT,
    CONTRACT,
    LOST_RESULT,
    MISMATCH
  }

  public UUID id;
  public long taskId;
  public String key;
  public String name;
  public String description;
  public Task.Type type;
  public long workerId;
  public String externalId;
  public Exchange exchange = Exchange.PENDING;
  public Problem problem;
  public boolean temporaryFailure;
  public boolean conflictingResults;
  public ExternalResult result;
  public ExternalResult confirmedResult;
  public Instant createdAt;

  public Operation() {}

  public static Operation start(Task task, UUID id, String key, Instant now) {
    Operation operation = new Operation();
    operation.id = id;
    operation.taskId = task.id;
    operation.key = key;
    operation.name = task.name;
    operation.description = task.description;
    operation.type = task.type;
    operation.workerId = task.workerId;
    operation.createdAt = now;
    return operation;
  }

  public ExternalResult acceptableResult() {
    if (problem == Problem.MISMATCH) return confirmedResult;
    return problem == null ? result : null;
  }

  public boolean observe(ExternalResult incoming, boolean reconciliation) {
    return observe(incoming, reconciliation, false);
  }

  public boolean observe(ExternalResult incoming, boolean reconciliation, boolean completed) {
    require(externalId != null && externalId.equals(incoming.id()), "InvalidResult");
    exchange = Exchange.ACKNOWLEDGED;
    temporaryFailure = false;
    if (completed && result != null && result.equals(incoming)) {
      problem = null;
      conflictingResults = false;
      confirmedResult = null;
      return false;
    }
    if (result != null && !result.equals(incoming)) {
      boolean previouslyDetected = conflictingResults || problem == Problem.MISMATCH;
      problem = Problem.MISMATCH;
      conflictingResults = true;
      confirmedResult = reconciliation && previouslyDetected ? incoming : null;
      return false;
    }
    boolean changed = result == null;
    result = incoming;
    if (conflictingResults || problem == Problem.MISMATCH) {
      problem = Problem.MISMATCH;
      if (reconciliation) confirmedResult = incoming;
    } else {
      problem = null;
      confirmedResult = null;
    }
    return changed;
  }

  public void restart() {
    require(problem != Problem.LOST_RESULT, "InvalidState");
    if (conflictingResults) problem = Problem.MISMATCH;
    else if (problem != Problem.MISMATCH && result == null) problem = null;
    confirmedResult = null;
    temporaryFailure = false;
    exchange = Exchange.PENDING;
  }
}

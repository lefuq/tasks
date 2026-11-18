package ru.tz.exchange.application;

import static ru.tz.exchange.domain.Failure.require;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import ru.tz.exchange.domain.*;

public final class ExchangeService {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ExchangeService.class);
  private record Dispatch(Ports.Job job, Operation operation, Ports.Worker worker, boolean post) {}

  private final Ports.Store store;
  private final Ports.Transactions transactions;
  private final Ports.Time time;
  private final Ports.Keys keys;
  private final Ports.External external;
  private final Ports.Encoding encoding;
  private final RetryPolicy retries;

  public ExchangeService(
      Ports.Store store,
      Ports.Transactions transactions,
      Ports.Time time,
      Ports.Keys keys,
      Ports.External external,
      Ports.Encoding encoding,
      RetryPolicy retries) {
    this.store = store;
    this.transactions = transactions;
    this.time = time;
    this.keys = keys;
    this.external = external;
    this.encoding = encoding;
    this.retries = retries;
  }

  public boolean tick() {
    Ports.Job claimed =
        transactions.run(
            () -> {
              Instant now = time.now();
              return store.claim(now, now.plusSeconds(30), keys.next());
            });
    if (claimed == null) return false;
    Dispatch dispatch =
        transactions.run(
            () -> {
              Operation operation = store.operation(claimed.operationId());
              Task task = store.task(operation.taskId, true);
              operation = store.operation(claimed.operationId());
              if (!store.owns(claimed, time.now())) return null;
              boolean reconciliation =
                  claimed.kind().equals("RECONCILE") || claimed.kind().equals("AUTO_RECONCILE");
              if (!operation.id.equals(task.activeOperationId)
                  || !reconciliation
                      && (task.state == Task.State.COMPLETED
                          || task.state == Task.State.RETURNED
                          || task.state == Task.State.WAITING_TO_SEND)) {
                store.finish(claimed);
                return null;
              }
              operation.exchange = Operation.Exchange.DELIVERING;
              store.save(operation);
              return new Dispatch(
                  claimed,
                  operation,
                  store.worker(operation.workerId),
                  operation.externalId == null);
            });
    if (dispatch == null) return false;
    Ports.Reply reply = external.request(dispatch.operation(), dispatch.worker(), dispatch.post());
    apply(dispatch.job(), reply, dispatch.post());
    return true;
  }

  public void apply(Ports.Job job, Ports.Reply reply, boolean post) {
    transactions.run(
        () -> {
          Operation operation = store.operation(job.operationId());
          Task task = store.task(operation.taskId, true);
          operation = store.operation(job.operationId());
          if (!store.owns(job, time.now())) {
            store.audit(
                task.id,
                null,
                time.now(),
                "discarded_response",
                List.of(operation.id.toString(), reply));
            return null;
          }
          Instant now = time.now();
          store.attempt(job, now, reply);
          boolean manualReconciliation = job.kind().equals("RECONCILE");
          boolean reconciliation = manualReconciliation || job.kind().equals("AUTO_RECONCILE");
          String local = encoding.encode(operation);
          if (reconciliation) store.reconcile(operation, now, local, reply.body());
          if (!operation.id.equals(task.activeOperationId)
              || task.state == Task.State.RETURNED
              || task.state == Task.State.WAITING_TO_SEND
              || !reconciliation && task.state == Task.State.COMPLETED) {
            store.audit(
                task.id, null, now, "stale_response", List.of(operation.id.toString(), reply));
            store.finish(job);
            return null;
          }
          if (reply.networkFailure() || reply.status() >= 500) {
            LOG.warn("Exchange failure: {}", reply.networkFailure() ? "NetworkFailure" : "TemporaryFailure");
            Duration delay = retries.afterFailure(job.failures());
            operation.temporaryFailure = true;
            operation.exchange =
                reply.networkFailure() ? Operation.Exchange.UNKNOWN : Operation.Exchange.RETRY_WAIT;
            operation.problem =
                delay == null ? Operation.Problem.EXHAUSTED : Operation.Problem.TEMPORARY;
            store.problem(operation, now, local, reply.body());
            if (delay == null) {
              operation.exchange = Operation.Exchange.NEEDS_REVIEW;
              store.finish(job);
            } else
              store.reschedule(
                  job,
                  reply.networkFailure() ? "AUTO_RECONCILE" : job.kind(),
                  job.failures() + 1,
                  now.plus(delay));
          } else {
            try {
              if (post && reply.status() == 202) {
                String id = external.acceptedId(reply.body());
                require(id != null && !id.isBlank(), "InvalidResult");
                if (operation.externalId != null)
                  require(operation.externalId.equals(id), "InvalidResult");
                operation.externalId = id;
                operation.exchange = Operation.Exchange.ACKNOWLEDGED;
                store.reschedule(
                    job, reconciliation ? job.kind() : "GET", job.failures(), now.plusSeconds(10));
              } else if (!post && reply.status() == 202) {
                operation.exchange = Operation.Exchange.ACKNOWLEDGED;
                store.reschedule(job, job.kind(), job.failures(), now.plusSeconds(10));
              } else if (!post && reply.status() == 200) {
                ExternalResult incoming = external.result(reply.body());
                boolean changed =
                    operation.observe(
                        incoming, manualReconciliation, task.state == Task.State.COMPLETED);
                if (operation.problem == Operation.Problem.MISMATCH)
                  store.problem(operation, now, local, reply.body());
                else store.resolve(operation.id, 0, now, "recovered");
                if (task.state != Task.State.COMPLETED
                    && (changed || task.state == Task.State.EXECUTING)) task.received();
                store.finish(job);
                store.audit(
                    task.id,
                    null,
                    now,
                    changed ? "result_received" : "result_observed",
                    List.of(operation.id.toString(), incoming));
              } else {
                operation.problem =
                    !post && reply.status() == 404
                        ? Operation.Problem.LOST_RESULT
                        : Operation.Problem.CONTRACT;
                operation.exchange = Operation.Exchange.NEEDS_REVIEW;
                store.problem(operation, now, local, reply.body());
                store.finish(job);
              }
            } catch (Failure exception) {
              LOG.warn("Exchange failure: InvalidResult");
              operation.problem =
                  post ? Operation.Problem.CONTRACT : Operation.Problem.INVALID_RESULT;
              operation.exchange = Operation.Exchange.NEEDS_REVIEW;
              store.problem(operation, now, local, reply.body());
              store.finish(job);
            }
          }
          store.save(operation);
          store.save(task);
          return null;
        });
  }
}

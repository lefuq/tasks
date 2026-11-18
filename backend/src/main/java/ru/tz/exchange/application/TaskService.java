package ru.tz.exchange.application;

import static ru.tz.exchange.domain.Failure.require;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import ru.tz.exchange.domain.*;

public final class TaskService {
  public record View(
      long id,
      String name,
      String description,
      Task.Type type,
      String typeName,
      Task.State state,
      long version,
      long authorId,
      Long operatorId,
      Long workerId,
      UUID operationId,
      Operation.Exchange exchange,
      Operation.Problem problem,
      String externalId,
      ExternalResult result,
      List<String> actions) {}

  public record Command(long version, String description, Task.Type type, Long workerId) {}

  private final Ports.Store store;
  private final Ports.Transactions transactions;
  private final Ports.Time time;
  private final Ports.Keys keys;
  private final Ports.Encoding encoding;

  public TaskService(
      Ports.Store store,
      Ports.Transactions transactions,
      Ports.Time time,
      Ports.Keys keys,
      Ports.Encoding encoding) {
    this.store = store;
    this.transactions = transactions;
    this.time = time;
    this.keys = keys;
    this.encoding = encoding;
  }

  private View view(Actor actor, Task task) {
    Operation operation =
        task.activeOperationId == null ? null : store.operation(task.activeOperationId);
    ExternalResult result = operation == null ? null : operation.result;
    return new View(
        task.id,
        task.name,
        task.description,
        task.type,
        store.taskTypes().stream()
            .filter(type -> type.code() == task.type)
            .map(Ports.TaskType::name)
            .findFirst()
            .orElseThrow(),
        task.state,
        task.version,
        task.authorId,
        task.operatorId,
        task.workerId,
        task.activeOperationId,
        operation == null ? null : operation.exchange,
        operation == null ? null : operation.problem,
        operation == null ? null : operation.externalId,
        result,
        task.actions(actor, operation));
  }

  public View get(Actor actor, long id) {
    return transactions.run(
        () -> {
          Task task = store.task(id, false);
          task.readableBy(actor);
          return view(actor, task);
        });
  }

  public Ports.Page<View> list(Actor actor, Task.Type type, int page, int size) {
    require(page >= 0 && size >= 1 && size <= 100, "Validation");
    return transactions.run(
        () -> {
          Ports.Page<Task> tasks = store.tasks(actor, type, page, size);
          return new Ports.Page<>(
              tasks.items().stream().map(task -> view(actor, task)).toList(),
              tasks.total(),
              page,
              size);
        });
  }

  public List<View> problems(Actor actor) {
    return transactions.run(
        () -> store.problemTasks(actor).stream().map(task -> view(actor, task)).toList());
  }

  public List<Ports.TaskType> taskTypes() {
    return store.taskTypes();
  }

  public List<Ports.Worker> workers() {
    return store.workers();
  }

  public List<Ports.History> history(Actor actor, long id) {
    return transactions.run(
        () -> {
          store.task(id, false).readableBy(actor);
          return store.history(id);
        });
  }

  public byte[] file(Actor actor, long id) {
    return transactions.run(
        () -> {
          Task task = store.task(id, false);
          task.readableBy(actor);
          require(task.activeOperationId != null, "NotFound");
          ExternalResult result = store.operation(task.activeOperationId).result;
          require(result != null, "NotFound");
          return result.bytes();
        });
  }

  private View idempotent(Actor actor, String key, String fingerprint, Supplier<View> action) {
    require(key != null && !key.isBlank() && key.length() <= 200, "IdempotencyKeyRequired");
    return transactions.run(
        () -> {
          store.lockUser(actor.id());
          String previous = store.replay(actor.id(), key, fingerprint);
          if (previous != null) return encoding.decode(previous, View.class);
          View response = action.get();
          store.remember(actor.id(), key, fingerprint, encoding.encode(response));
          return response;
        });
  }

  public View create(Actor actor, String key, String name, String description, Task.Type type) {
    String fingerprint =
        encoding.encode(
            List.of(
                "create", String.valueOf(name), String.valueOf(description), String.valueOf(type)));
    return idempotent(
        actor,
        key,
        fingerprint,
        () -> {
          Task task = Task.create(actor, name, description, type);
          store.lockService(actor.serviceId());
          store.insert(task);
          store.audit(task.id, actor.id(), time.now(), "created", encoding.encode(task));
          Long operator = store.leastLoadedOperator(actor.serviceId());
          if (operator != null) {
            task.assign(operator);
            store.save(task);
            store.audit(task.id, null, time.now(), "assigned", "Назначен оператор #" + operator);
          }
          return view(actor, task);
        });
  }

  public void assignBacklog(long serviceId) {
    transactions.run(
        () -> {
          store.lockService(serviceId);
          for (Task task : store.unassigned(serviceId)) {
            Long operator = store.leastLoadedOperator(serviceId);
            if (operator == null) break;
            task.assign(operator);
            store.save(task);
            store.audit(task.id, null, time.now(), "assigned", "Назначен оператор #" + operator);
          }
          return null;
        });
  }

  public View command(Actor actor, long id, String action, String key, Command command) {
    String fingerprint = encoding.encode(List.of(id, action, command));
    return idempotent(
        actor,
        key,
        fingerprint,
        () -> {
          Task task = store.task(id, true);
          task.readableBy(actor);
          task.expectedVersion(command.version());
          Operation operation =
              task.activeOperationId == null ? null : store.operation(task.activeOperationId);
          String before = encoding.encode(task);
          Instant now = time.now();
          switch (action) {
            case "edit" -> task.edit(actor, command.description(), command.type());
            case "worker" -> {
              require(command.workerId() != null, "Validation");
              store.worker(command.workerId());
              task.chooseWorker(actor, command.workerId(), operation);
              if (operation != null
                  && (task.state == Task.State.EXECUTING
                      || task.state == Task.State.AWAITING_ACCEPTANCE)
                  && operation.problem != Operation.Problem.LOST_RESULT) {
                store.cancel(operation.id);
                operation.workerId = command.workerId();
                operation.restart();
                store.save(operation);
                store.enqueue(operation.id, operation.externalId == null ? "POST" : "GET", now);
              }
            }
            case "send" -> {
              UUID operationId = keys.next();
              task.send(actor, operationId, operation);
              if (operation != null) {
                store.cancel(operation.id);
                store.resolve(operation.id, actor.id(), now, "replaced");
              }
              operation = Operation.start(task, operationId, keys.next().toString(), now);
              store.save(operation);
              store.enqueue(operation.id, "POST", now);
            }
            case "return" -> {
              task.returnToAuthor(actor, operation);
              store.cancel(operation.id);
              store.resolve(operation.id, actor.id(), now, "returned");
            }
            case "retry", "reconcile" -> {
              task.operator(actor);
              require(task.actions(actor, operation).contains(action), "InvalidState");
              store.cancel(operation.id);
              if (action.equals("retry")) {
                operation.restart();
              }
              if (action.equals("reconcile")) operation.confirmedResult = null;
              store.save(operation);
              store.enqueue(
                  operation.id,
                  action.equals("reconcile")
                      ? "RECONCILE"
                      : operation.externalId == null ? "POST" : "GET",
                  now);
            }
            case "accept" -> {
              task.accept(actor, operation);
              operation.result = operation.acceptableResult();
              operation.confirmedResult = null;
              operation.conflictingResults = false;
              operation.problem = null;
              store.save(operation);
              store.resolve(operation.id, actor.id(), now, "accepted");
              store.cancel(operation.id);
            }
            default -> throw new Failure("NotFound");
          }
          store.save(task);
          store.audit(id, actor.id(), now, action, List.of(before, encoding.encode(task)));
          return view(actor, task);
        });
  }
}

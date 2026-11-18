package ru.tz.exchange.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import ru.tz.exchange.domain.*;

public final class Ports {
  private Ports() {}

  public interface Time {
    Instant now();
  }

  public interface Keys {
    UUID next();
  }

  public interface Jitter {
    int seconds();
  }

  public interface Transactions {
    <T> T run(Supplier<T> action);
  }

  public record TaskType(Task.Type code, String name) {}

  public record Worker(long id, String name, String endpoint) {}

  public record Page<T>(List<T> items, long total, int page, int size) {}

  public record Job(
      UUID id,
      UUID operationId,
      String kind,
      int failures,
      Instant dueAt,
      UUID leaseToken,
      Instant startedAt) {}

  public record Reply(int status, String body, boolean networkFailure) {}

  public record History(long id, Instant at, Long actorId, String action, String details) {}

  public interface External {
    Reply request(Operation operation, Worker worker, boolean post);

    String acceptedId(String body);

    ExternalResult result(String body);
  }

  public interface Store {
    void lockService(long serviceId);

    Long leastLoadedOperator(long serviceId);

    List<Task> unassigned(long serviceId);

    Task task(long id, boolean lock);

    Task insert(Task task);

    void save(Task task);

    Operation operation(UUID id);

    void save(Operation operation);

    Worker worker(long id);

    List<Worker> workers();

    List<TaskType> taskTypes();

    Page<Task> tasks(Actor actor, Task.Type type, int page, int size);

    List<Task> problemTasks(Actor actor);

    void audit(long taskId, Long actorId, Instant at, String action, Object details);

    List<History> history(long taskId);

    String replay(long actorId, String key, String fingerprint);

    void remember(long actorId, String key, String fingerprint, String response);

    void enqueue(UUID operationId, String kind, Instant dueAt);

    void cancel(UUID operationId);

    Job claim(Instant now, Instant until, UUID leaseToken);

    boolean owns(Job job, Instant now);

    void finish(Job job);

    void reschedule(Job job, String kind, int failures, Instant dueAt);

    void attempt(Job job, Instant endedAt, Reply reply);

    void problem(Operation operation, Instant at, String local, String external);

    void reconcile(Operation operation, Instant at, String local, String external);

    void resolve(UUID operationId, long actorId, Instant at, String decision);

    void lockUser(long actorId);
  }

  public record Identity(Actor actor, String username, String passwordHash) {}

  public interface Directory {
    Identity identity(String username);

    long addService(String name);

    long addUser(
        long serviceId, String username, String name, String passwordHash, Actor.Role role);

    void addWorker(String name, String endpoint);

    boolean empty();
  }

  public interface Encoding {
    String encode(Object object);

    <T> T decode(String text, Class<T> type);
  }
}

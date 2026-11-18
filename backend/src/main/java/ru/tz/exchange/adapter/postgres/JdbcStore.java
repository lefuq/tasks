package ru.tz.exchange.adapter.postgres;

import static ru.tz.exchange.domain.Failure.require;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.tz.exchange.application.Ports;
import ru.tz.exchange.domain.*;

public final class JdbcStore implements Ports.Store {
  private final JdbcTemplate jdbc;
  private final Ports.Encoding encoding;

  public JdbcStore(JdbcTemplate jdbc, Ports.Encoding encoding) {
    this.jdbc = jdbc;
    this.encoding = encoding;
  }

  public void lockService(long id) {
    jdbc.queryForObject("select id from services where id=? for update", Long.class, id);
  }

  public void lockUser(long id) {
    jdbc.queryForObject("select id from users where id=? for update", Long.class, id);
  }

  public Long leastLoadedOperator(long serviceId) {
    List<Long> operators =
        jdbc.queryForList(
            """
            select u.id from users u left join tasks t on t.operator_id=u.id and t.state<>'COMPLETED'
            where u.service_id=? and u.role='OPERATOR' group by u.id order by count(t.id),u.id limit 1
            """,
            Long.class,
            serviceId);
    return operators.isEmpty() ? null : operators.getFirst();
  }

  private Task taskRow(ResultSet row, int index) throws SQLException {
    Task task = new Task();
    task.id = row.getLong("id");
    task.serviceId = row.getLong("service_id");
    task.authorId = row.getLong("author_id");
    task.operatorId = row.getObject("operator_id", Long.class);
    task.workerId = row.getObject("worker_id", Long.class);
    task.activeOperationId = row.getObject("active_operation_id", UUID.class);
    task.name = row.getString("name");
    task.description = row.getString("description");
    task.type = Task.Type.valueOf(row.getString("type"));
    task.state = Task.State.valueOf(row.getString("state"));
    task.version = row.getLong("version");
    return task;
  }

  public List<Task> unassigned(long id) {
    return jdbc.query(
        "select * from tasks where service_id=? and state='NEW' order by id for update",
        this::taskRow,
        id);
  }

  public Task task(long id, boolean lock) {
    List<Task> tasks =
        jdbc.query(
            "select * from tasks where id=?" + (lock ? " for update" : ""), this::taskRow, id);
    require(!tasks.isEmpty(), "NotFound");
    return tasks.getFirst();
  }

  public Task insert(Task task) {
    task.id =
        jdbc.queryForObject(
            """
            insert into tasks(service_id,author_id,name,description,type,state,version)
            values(?,?,?,?,?,'NEW',0) returning id
            """,
            Long.class,
            task.serviceId,
            task.authorId,
            task.name,
            task.description,
            task.type.name());
    return task;
  }

  public void save(Task task) {
    jdbc.update(
        """
        update tasks set operator_id=?,worker_id=?,active_operation_id=?,description=?,type=?,state=?,version=? where id=?
        """,
        task.operatorId,
        task.workerId,
        task.activeOperationId,
        task.description,
        task.type.name(),
        task.state.name(),
        task.version,
        task.id);
  }

  public Operation operation(UUID id) {
    List<String> operations =
        jdbc.queryForList("select document::text from operations where id=?", String.class, id);
    require(!operations.isEmpty(), "NotFound");
    return encoding.decode(operations.getFirst(), Operation.class);
  }

  public void save(Operation operation) {
    jdbc.update(
        """
        insert into operations(id,task_id,business_key,worker_id,external_id,document)
        values(?,?,?,?,?,?::jsonb) on conflict(id) do update set worker_id=excluded.worker_id,external_id=excluded.external_id,document=excluded.document
        """,
        operation.id,
        operation.taskId,
        operation.key,
        operation.workerId,
        operation.externalId,
        encoding.encode(operation));
  }

  public Ports.Worker worker(long id) {
    List<Ports.Worker> workers =
        jdbc.query(
            "select * from workers where id=?",
            (r, i) ->
                new Ports.Worker(r.getLong("id"), r.getString("name"), r.getString("endpoint")),
            id);
    require(!workers.isEmpty(), "NotFound");
    return workers.getFirst();
  }

  public List<Ports.Worker> workers() {
    return jdbc.query(
        "select * from workers order by id",
        (r, i) -> new Ports.Worker(r.getLong("id"), r.getString("name"), r.getString("endpoint")));
  }

  public List<Ports.TaskType> taskTypes() {
    return jdbc.query(
        "select code, name from task_types order by code",
        (r, i) -> new Ports.TaskType(Task.Type.valueOf(r.getString("code")), r.getString("name")));
  }

  public Ports.Page<Task> tasks(Actor actor, Task.Type type, int page, int size) {
    String where =
        " where service_id=? and (?='OPERATOR' or author_id=?) and (cast(? as text) is null or"
            + " type=?)";
    Object[] params = {
      actor.serviceId(),
      actor.role().name(),
      actor.id(),
      type == null ? null : type.name(),
      type == null ? null : type.name()
    };
    Long total = jdbc.queryForObject("select count(*) from tasks" + where, Long.class, params);
    List<Task> tasks =
        jdbc.query(
            "select * from tasks" + where + " order by id desc limit ? offset ?",
            this::taskRow,
            actor.serviceId(),
            actor.role().name(),
            actor.id(),
            type == null ? null : type.name(),
            type == null ? null : type.name(),
            size,
            (long) page * size);
    return new Ports.Page<>(tasks, total, page, size);
  }

  public List<Task> problemTasks(Actor actor) {
    return jdbc.query(
        """
        select distinct t.* from tasks t join problems p on p.task_id=t.id where p.resolved_at is null
        and t.service_id=? and (?='OPERATOR' or t.author_id=?) order by t.id desc
        """,
        this::taskRow,
        actor.serviceId(),
        actor.role().name(),
        actor.id());
  }

  public void audit(long taskId, Long actorId, Instant at, String action, Object details) {
    jdbc.update(
        "insert into audit(task_id,actor_id,at,action,details) values(?,?,?,?,?::jsonb)",
        taskId,
        actorId,
        Timestamp.from(at),
        action,
        encoding.encode(details));
  }

  public List<Ports.History> history(long id) {
    return jdbc.query(
        "select * from audit where task_id=? order by id",
        (r, i) ->
            new Ports.History(
                r.getLong("id"),
                r.getTimestamp("at").toInstant(),
                r.getObject("actor_id", Long.class),
                r.getString("action"),
                r.getString("details")),
        id);
  }

  public String replay(long actorId, String key, String fingerprint) {
    List<String[]> requests =
        jdbc.query(
            "select fingerprint,response::text from api_requests where user_id=? and key=?",
            (r, i) -> new String[] {r.getString(1), r.getString(2)},
            actorId,
            key);
    if (requests.isEmpty()) return null;
    require(requests.getFirst()[0].equals(fingerprint), "IdempotencyConflict");
    return requests.getFirst()[1];
  }

  public void remember(long actorId, String key, String fingerprint, String response) {
    jdbc.update(
        "insert into api_requests(user_id,key,fingerprint,response) values(?,?,?,?::jsonb)",
        actorId,
        key,
        fingerprint,
        response);
  }

  public void enqueue(UUID operationId, String kind, Instant dueAt) {
    jdbc.update(
        "insert into exchange_jobs(operation_id,kind,due_at) values(?,?,?)",
        operationId,
        kind,
        Timestamp.from(dueAt));
  }

  public void cancel(UUID id) {
    jdbc.update("delete from exchange_jobs where operation_id=?", id);
  }

  public Ports.Job claim(Instant now, Instant until, UUID token) {
    List<Ports.Job> jobs =
        jdbc.query(
            """
            update exchange_jobs set lease_token=?,locked_until=?,started_at=? where id=(
            select id from exchange_jobs where due_at<=? and (locked_until is null or locked_until<=?)
            order by due_at,id for update skip locked limit 1)
            returning *
            """,
            (r, i) ->
                new Ports.Job(
                    r.getObject("id", UUID.class),
                    r.getObject("operation_id", UUID.class),
                    r.getString("kind"),
                    r.getInt("failures"),
                    r.getTimestamp("due_at").toInstant(),
                    r.getObject("lease_token", UUID.class),
                    r.getTimestamp("started_at").toInstant()),
            token,
            Timestamp.from(until),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now));
    return jobs.isEmpty() ? null : jobs.getFirst();
  }

  public boolean owns(Ports.Job job, Instant now) {
    return !jdbc.queryForList(
            "select id from exchange_jobs where id=? and lease_token=? and locked_until>? for"
                + " update",
            UUID.class,
            job.id(),
            job.leaseToken(),
            Timestamp.from(now))
        .isEmpty();
  }

  public void finish(Ports.Job job) {
    jdbc.update(
        "delete from exchange_jobs where id=? and lease_token=?", job.id(), job.leaseToken());
  }

  public void reschedule(Ports.Job job, String kind, int failures, Instant dueAt) {
    jdbc.update(
        "update exchange_jobs set kind=?,failures=?,due_at=?,lease_token=null,locked_until=null"
            + " where id=? and lease_token=?",
        kind,
        failures,
        Timestamp.from(dueAt),
        job.id(),
        job.leaseToken());
  }

  public void attempt(Ports.Job job, Instant endedAt, Ports.Reply reply) {
    jdbc.update(
        "insert into"
            + " exchange_attempts(operation_id,started_at,finished_at,status,network_failure,response)"
            + " values(?,?,?,?,?,?)",
        job.operationId(),
        Timestamp.from(job.startedAt()),
        Timestamp.from(endedAt),
        reply.status(),
        reply.networkFailure(),
        reply.body());
    Operation operation = operation(job.operationId());
    audit(
        operation.taskId,
        null,
        endedAt,
        "exchange_attempt",
        List.of(job.operationId().toString(), job.kind(), job.startedAt(), reply));
  }

  public void problem(Operation operation, Instant at, String local, String external) {
    jdbc.update(
        "insert into problems(task_id,operation_id,kind,at,local_observation,external_observation)"
            + " values(?,?,?,?,?::jsonb,?)",
        operation.taskId,
        operation.id,
        operation.problem.name(),
        Timestamp.from(at),
        local,
        external);
    audit(
        operation.taskId,
        null,
        at,
        "problem",
        List.of(operation.id.toString(), operation.problem, local, external));
  }

  public void reconcile(Operation operation, Instant at, String local, String external) {
    jdbc.update(
        "insert into reconciliations(operation_id,at,local_observation,external_observation)"
            + " values(?,?,?::jsonb,?)",
        operation.id,
        Timestamp.from(at),
        local,
        external);
    audit(
        operation.taskId,
        null,
        at,
        "reconciliation",
        List.of(operation.id.toString(), local, external));
  }

  public void resolve(UUID id, long actorId, Instant at, String decision) {
    jdbc.update(
        "update problems set resolved_at=?,resolved_by=?,decision=? where operation_id=? and"
            + " resolved_at is null",
        Timestamp.from(at),
        actorId == 0 ? null : actorId,
        decision,
        id);
  }
}

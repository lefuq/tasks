package ru.tz.exchange.adapter.http;

import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.tz.exchange.application.*;
import ru.tz.exchange.domain.*;

@RestController
@RequestMapping("/api")
public class TaskController {
  public record Create(String name, String description, Task.Type type) {}

  public record Edit(Long version, String description, Task.Type type) {}

  public record WorkerChoice(Long version, Long workerId) {}

  public record Version(Long version) {}

  public record Worker(long id, String name) {}

  private final TaskService tasks;
  private final Users users;

  public TaskController(TaskService tasks, Users users) {
    this.tasks = tasks;
    this.users = users;
  }

  @GetMapping("/task-types")
  public List<Ports.TaskType> taskTypes() {
    return tasks.taskTypes();
  }

  @GetMapping("/workers")
  public List<Worker> workers() {
    return tasks.workers().stream().map(w -> new Worker(w.id(), w.name())).toList();
  }

  @GetMapping("/tasks")
  public Ports.Page<TaskService.View> list(
      Authentication auth,
      @RequestParam(required = false) Task.Type type,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return tasks.list(users.actor(auth), type, page, size);
  }

  @PostMapping("/tasks")
  @ResponseStatus(HttpStatus.CREATED)
  public TaskService.View create(
      Authentication auth, @RequestHeader("Idempotency-Key") String key, @RequestBody Create body) {
    if (body.name() == null || body.description() == null || body.type() == null)
      throw new MissingFields();
    return tasks.create(users.actor(auth), key, body.name(), body.description(), body.type());
  }

  @GetMapping("/tasks/{id}")
  public TaskService.View get(Authentication auth, @PathVariable long id) {
    return tasks.get(users.actor(auth), id);
  }

  private TaskService.Command command(
      Long version, String description, Task.Type type, Long workerId) {
    if (version == null) throw new MissingFields();
    return new TaskService.Command(version, description, type, workerId);
  }

  @PatchMapping("/tasks/{id}")
  public TaskService.View edit(
      Authentication auth,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Edit body) {
    if (body.description() == null || body.type() == null) throw new MissingFields();
    return tasks.command(
        users.actor(auth),
        id,
        "edit",
        key,
        command(body.version(), body.description(), body.type(), null));
  }

  @PutMapping("/tasks/{id}/worker")
  public TaskService.View worker(
      Authentication auth,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody WorkerChoice body) {
    if (body.workerId() == null) throw new MissingFields();
    return tasks.command(
        users.actor(auth), id, "worker", key, command(body.version(), null, null, body.workerId()));
  }

  @PostMapping("/tasks/{id}/{action:send|retry|return|accept|reconcile}")
  public TaskService.View action(
      Authentication auth,
      @PathVariable long id,
      @PathVariable String action,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Version body) {
    return tasks.command(
        users.actor(auth), id, action, key, command(body.version(), null, null, null));
  }

  @GetMapping("/tasks/{id}/history")
  public List<Ports.History> history(Authentication auth, @PathVariable long id) {
    return tasks.history(users.actor(auth), id);
  }

  @GetMapping("/tasks/{id}/result/file")
  public ResponseEntity<byte[]> file(Authentication auth, @PathVariable long id) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=task-" + id + ".md")
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(tasks.file(users.actor(auth), id));
  }

  @GetMapping("/problems")
  public List<TaskService.View> problems(Authentication auth) {
    return tasks.problems(users.actor(auth));
  }
}

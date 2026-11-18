package ru.tz.emulator.http;

import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import ru.tz.emulator.application.Operations;
import ru.tz.emulator.domain.*;

@RestController
@RequestMapping("/admin")
public final class AdminController {
    public record Inspection(UUID id, Submission snapshot, String worker, String state, int execution_count,
                             Instant started_at, Instant ready_at, Instant finished_at, Operation.Result result) {}
    private final Operations operations;

    public AdminController(Operations operations) { this.operations = operations; }

    @GetMapping("/operations/{id}")
    public Inspection inspect(@PathVariable UUID id) {
        Operation operation = operations.inspect(id);
        return new Inspection(operation.id(), operation.submission(), operation.worker(),
                operation.ready() ? "completed" : "running", operation.executionCount(), operation.startedAt(),
                operation.readyAt(), operation.finishedAt(), operation.result());
    }

    @GetMapping("/workers/{worker}")
    public Settings settings(@PathVariable String worker) { return operations.settings(worker); }

    @PutMapping("/workers/{worker}")
    public Settings configure(@PathVariable String worker, @RequestBody Settings settings) {
        operations.configure(worker, settings);
        return settings;
    }

    @DeleteMapping("/workers/{worker}")
    public Settings reset(@PathVariable String worker) {
        operations.configure(worker, Settings.NORMAL);
        return Settings.NORMAL;
    }
}

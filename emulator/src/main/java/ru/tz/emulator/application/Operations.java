package ru.tz.emulator.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tz.emulator.domain.*;

public final class Operations {
    private static final Logger log = LoggerFactory.getLogger(Operations.class);
    private final Registry registry;
    private final Clock clock;
    private final ExecutionPolicy policy;

    public Operations(Registry registry, Clock clock, ExecutionPolicy policy) {
        this.registry = registry;
        this.clock = clock;
        this.policy = policy;
    }

    public Operation accept(String worker, String key, Submission submission) {
        available(worker);
        if (key == null || key.isBlank()) throw new Problem(422, "MissingIdempotencyKey");
        Instant now = clock.instant();
        Operation operation = registry.register(new Operation(UUID.randomUUID(), key, submission, worker,
                now, now.plusSeconds(policy.seconds()), null, policy.outcome(registry.settings(worker).outcome()), 1, null));
        if (!operation.submission().equals(submission)) throw new Problem(409, "IdempotencyConflict");
        log.info("Accepted operation {} through worker {}", operation.id(), worker);
        return operation;
    }

    public Operation inspect(UUID id) {
        Operation operation = registry.operation(id);
        if (operation == null) throw new Problem(404, "UnknownOperation");
        return operation;
    }

    public Operation get(String worker, UUID id) {
        available(worker);
        if (settings(worker).fault() == Settings.Fault.lost_result) throw new Problem(404, "UnknownOperation");
        return inspect(id);
    }

    public Settings settings(String worker) { return registry.settings(worker); }

    public void configure(String worker, Settings settings) {
        registry.settings(worker, settings);
        log.info("Worker {} configured: {}", worker, settings);
    }

    public int tick() {
        int completed = registry.completeDue(clock.instant(), operation -> {
            Operation completion = operation.complete(clock.instant(),
                    operation.outcome() == Settings.Outcome.fail ? "" : policy.content());
            return completion;
        });
        if (completed > 0) log.info("Completed {} operations", completed);
        return completed;
    }

    private void available(String worker) {
        if (settings(worker).fault() == Settings.Fault.unavailable) throw new Problem(503, "Unavailable");
    }
}

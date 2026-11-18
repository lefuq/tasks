package ru.tz.emulator.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.tz.emulator.application.Operations;

@Component
@ConditionalOnProperty(name = "emulator.execution-enabled", havingValue = "true", matchIfMissing = true)
public final class ExecutionScheduler {
    private final Operations operations;
    public ExecutionScheduler(Operations operations) { this.operations = operations; }
    @Scheduled(fixedDelay = 100)
    public void tick() { operations.tick(); }
}

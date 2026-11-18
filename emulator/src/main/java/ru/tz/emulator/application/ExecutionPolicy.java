package ru.tz.emulator.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.IntBinaryOperator;
import ru.tz.emulator.domain.Settings.Outcome;

public final class ExecutionPolicy {
    private final IntBinaryOperator random;

    public ExecutionPolicy(IntBinaryOperator random) { this.random = random; }

    public int seconds() { return random.applyAsInt(10, 61); }

    public Outcome outcome(Outcome configured) {
        return configured == Outcome.random
                ? (random.applyAsInt(0, 100) < 20 ? Outcome.fail : Outcome.success) : configured;
    }

    public String content() {
        return Base64.getEncoder().encodeToString(
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit.\n".getBytes(StandardCharsets.UTF_8));
    }
}

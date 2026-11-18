package ru.tz.emulator.domain;

import java.time.Instant;
import java.util.UUID;

public record Operation(UUID id, String key, Submission submission, String worker,
                        Instant startedAt, Instant readyAt, Instant finishedAt,
                        Settings.Outcome outcome, int executionCount, String data) {
    public record Result(UUID id, String status, long processing_time, String data) {}

    public boolean ready() { return finishedAt != null; }

    public Operation complete(Instant now, String content) {
        if (ready() || now.isBefore(readyAt)) return this;
        return new Operation(id, key, submission, worker, startedAt, readyAt, now, outcome,
                executionCount, outcome == Settings.Outcome.fail ? "" : content);
    }

    public Result result() {
        if (!ready()) return null;
        return new Result(id, outcome.name(), finishedAt.getEpochSecond() - startedAt.getEpochSecond(), data);
    }
}

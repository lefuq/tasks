package ru.tz.emulator.domain;

public final class Problem extends RuntimeException {
    private final int status;

    public Problem(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
}

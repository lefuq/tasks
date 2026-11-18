package ru.tz.emulator.domain;

public record Settings(Fault fault, Outcome outcome) {
    public enum Fault {
        normal, unavailable, lose_response, lost_result, invalid_json, unknown_status, wrong_id,
        negative_time, fractional_time, invalid_base64, oversized_data
    }
    public enum Outcome { random, success, fail }
    public static final Settings NORMAL = new Settings(Fault.normal, Outcome.random);

    public Settings {
        if (fault == null || outcome == null) throw new Problem(422, "MissingFields");
    }
}

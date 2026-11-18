package ru.tz.emulator.application;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import ru.tz.emulator.domain.Operation;
import ru.tz.emulator.domain.Settings.Fault;

public final class FaultResponses {
    public Object body(Operation.Result result, Fault fault) {
        if (fault == Fault.invalid_json) return "{";
        Map<String, Object> body = new LinkedHashMap<>(Map.of("id", result.id(), "status", result.status(),
                "processing_time", result.processing_time(), "data", result.data()));
        switch (fault) {
            case unknown_status -> body.put("status", "unknown");
            case wrong_id -> body.put("id", new UUID(result.id().getMostSignificantBits() ^ 1L,
                    result.id().getLeastSignificantBits()));
            case negative_time -> body.put("processing_time", -1);
            case fractional_time -> body.put("processing_time", 1.5);
            case invalid_base64 -> body.put("data", "%%%invalid%%%");
            case oversized_data -> body.put("data", Base64.getEncoder().encodeToString(new byte[102401]));
            default -> { }
        }
        return body;
    }
}

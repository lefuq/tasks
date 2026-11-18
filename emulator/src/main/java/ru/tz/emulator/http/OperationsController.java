package ru.tz.emulator.http;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import ru.tz.emulator.application.FaultResponses;
import ru.tz.emulator.application.Operations;
import ru.tz.emulator.domain.*;

@RestController
public final class OperationsController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OperationsController.class);
    private final Operations operations;
    private final FaultResponses faults;
    private final ScheduledExecutorService delays;
    private final String defaultWorker;

    public OperationsController(Operations operations, FaultResponses faults, ScheduledExecutorService delays,
                                @Value("${emulator.worker}") String defaultWorker) {
        this.operations = operations;
        this.faults = faults;
        this.delays = delays;
        this.defaultWorker = defaultWorker;
    }

    @PostMapping({"/operations", "/workers/{worker}/operations"})
    public ResponseEntity<?> accept(@PathVariable(required = false) String worker,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                   @RequestBody JsonNode body) {
        Submission submission = submission(body);
        Operation operation = operations.accept(worker == null ? defaultWorker : worker, key, submission);
        return ResponseEntity.accepted().body(Map.of("id", operation.id()));
    }

    @GetMapping({"/operations/{id}", "/workers/{worker}/operations/{id}"})
    public DeferredResult<ResponseEntity<?>> get(@PathVariable(required = false) String worker, @PathVariable UUID id) {
        String endpoint = worker == null ? defaultWorker : worker;
        Operation operation = operations.get(endpoint, id);
        DeferredResult<ResponseEntity<?>> response = new DeferredResult<>(10000L);
        if (!operation.ready()) response.setResult(ResponseEntity.accepted().body(Map.of("id", id)));
        else {
            Settings.Fault fault = operations.settings(endpoint).fault();
            if (fault != Settings.Fault.normal) log.warn("Response fault applied: {}", fault);
            if (fault == Settings.Fault.lose_response) {
                delays.schedule(() -> { response.setResult(ResponseEntity.status(504).body(Map.of("error", "ResponseLost"))); },
                        6, TimeUnit.SECONDS);
            } else response.setResult(ResponseEntity.ok().header("Content-Type", "application/json")
                    .body(faults.body(operation.result(), fault)));
        }
        return response;
    }

    private Submission submission(JsonNode body) {
        if (!body.isObject()) throw new Problem(400, "InvalidBody");
        for (String field : Set.of("name", "description", "type")) {
            if (!body.hasNonNull(field)) throw new Problem(422, "MissingFields");
            if (!body.get(field).isTextual()) throw new Problem(400, "InvalidBody");
        }
        if (body.size() != 3) throw new Problem(400, "InvalidBody");
        return new Submission(body.get("name").textValue(), body.get("description").textValue(), body.get("type").textValue());
    }
}

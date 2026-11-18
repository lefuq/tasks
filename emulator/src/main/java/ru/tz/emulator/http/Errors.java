package ru.tz.emulator.http;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import ru.tz.emulator.domain.Problem;

@RestControllerAdvice
public final class Errors {
    private static final Logger log = LoggerFactory.getLogger(Errors.class);

    @ExceptionHandler(Problem.class)
    public ResponseEntity<?> problem(Problem problem) {
        log.warn("Request rejected: status={}", problem.status());
        return ResponseEntity.status(problem.status()).body(Map.of("error", problem.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed(HttpMessageNotReadableException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof Problem problem) return problem(problem);
            cause = cause.getCause();
        }
        log.warn("Request rejected: malformed body");
        return ResponseEntity.badRequest().body(Map.of("error", "InvalidBody"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception exception) {
        log.error("Request failed: {}", exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("error", "ServerError"));
    }
}

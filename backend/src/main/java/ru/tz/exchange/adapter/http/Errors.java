package ru.tz.exchange.adapter.http;

import java.util.Map;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import ru.tz.exchange.domain.Failure;

@RestControllerAdvice
public class Errors {
  private static final Logger LOG = LoggerFactory.getLogger(Errors.class);

  @ExceptionHandler(Failure.class)
  public ResponseEntity<Map<String, String>> domain(Failure error) {
    int status =
        switch (error.code) {
          case "Forbidden" -> 403;
          case "NotFound" -> 404;
          case "VersionConflict", "IdempotencyConflict", "InvalidState" -> 409;
          default -> 422;
        };
    return response(status, error.code);
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<Map<String, String>> auth() {
    LOG.warn("Authentication rejected");
    return response(401, "Unauthorized");
  }

  @ExceptionHandler({MissingFields.class, MissingRequestHeaderException.class})
  public ResponseEntity<Map<String, String>> missing() {
    return response(422, "MissingFields");
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<Map<String, String>> malformed() {
    return response(400, "MalformedRequest");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> other(Exception error) {
    LOG.error("Request failed: {}", error.getClass().getSimpleName());
    return response(500, "ServerError");
  }

  private ResponseEntity<Map<String, String>> response(int status, String code) {
    return ResponseEntity.status(status).body(Map.of("code", code));
  }
}

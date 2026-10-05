package dev.queuedesk.http;

import dev.queuedesk.service.AdmissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
    public record Error(String code, String message) {}
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Error> invalid(Exception error) {
        String message = error instanceof IllegalArgumentException ? error.getMessage() : "Invalid JSON, header, or parameter";
        return ResponseEntity.badRequest().body(new Error("INVALID_REQUEST", message));
    }
    @ExceptionHandler(AdmissionService.Conflict.class)
    public ResponseEntity<Error> conflict() { return ResponseEntity.status(409).body(new Error("IDEMPOTENCY_CONFLICT", "Key already used with different payload")); }
    @ExceptionHandler(AdmissionService.Missing.class)
    public ResponseEntity<Error> missing() { return ResponseEntity.status(404).body(new Error("NOT_FOUND", "Unknown instruction")); }
    @ExceptionHandler(AdmissionService.Capacity.class)
    public ResponseEntity<Error> capacity() { return ResponseEntity.status(503).body(new Error("CAPACITY", "Restart the lab to clear its 1000-entry memory store")); }
}

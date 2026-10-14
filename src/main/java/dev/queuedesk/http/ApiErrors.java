package dev.queuedesk.http;

import dev.queuedesk.ops.LogContext;
import dev.queuedesk.repository.PageCursor;
import dev.queuedesk.service.AdmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    public record Error(String code, String message) {}
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class,
            PageCursor.BadCursor.class})
    public ResponseEntity<Error> invalid(Exception error) {
        String code = error instanceof PageCursor.BadCursor ? "INVALID_CURSOR" : "INVALID_REQUEST";
        String message = error instanceof IllegalArgumentException ? error.getMessage() : "Invalid JSON, header, or parameter";
        if (error instanceof PageCursor.BadCursor) message = "Unparseable pagination cursor";
        LogContext.set("outcome", code);
        log.warn("admission.rejected");
        return ResponseEntity.badRequest().body(new Error(code, message));
    }
    @ExceptionHandler(AdmissionService.Conflict.class)
    public ResponseEntity<Error> conflict() {
        LogContext.set("outcome", "IDEMPOTENCY_CONFLICT");
        log.warn("admission.rejected");
        return ResponseEntity.status(409).body(new Error("IDEMPOTENCY_CONFLICT", "Key already used with different payload"));
    }
    @ExceptionHandler(AdmissionService.Missing.class)
    public ResponseEntity<Error> missing() { return ResponseEntity.status(404).body(new Error("NOT_FOUND", "Unknown instruction")); }
    @ExceptionHandler(AdmissionService.Capacity.class)
    public ResponseEntity<Error> capacity() {
        LogContext.set("outcome", "CAPACITY");
        log.warn("admission.rejected");
        return ResponseEntity.status(503).body(new Error("CAPACITY", "Admission store reached the 1000-record lab limit; no data was cleared"));
    }
    // Dependency outages are 503 with a safe message: never leak connection strings.
    @ExceptionHandler({DataAccessResourceFailureException.class, QueryTimeoutException.class,
            CannotCreateTransactionException.class, TransactionSystemException.class})
    public ResponseEntity<Error> unavailable() {
        LogContext.set("outcome", "UNAVAILABLE");
        log.warn("admission.rejected");
        return ResponseEntity.status(503).body(new Error("UNAVAILABLE", "The lab database is unreachable; retry with the original key"));
    }
}

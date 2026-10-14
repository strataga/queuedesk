package dev.queuedesk.http;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The error-mapping contract, tested directly against the advice: outages
 * become safe 503s and client errors keep the shared code/message shape.
 * Live outage behavior is drilled in the runbooks.
 */
class ApiErrorsTest {
    private final ApiErrors errors = new ApiErrors();
    @Test void outageMapsToSafe503() {
        var unavailable = errors.unavailable();
        assertEquals(503, unavailable.getStatusCode().value());
        assertEquals("UNAVAILABLE", unavailable.getBody().code());
        assertFalse(unavailable.getBody().message().contains("jdbc"));
        assertEquals(503, errors.capacity().getStatusCode().value());
    }
    @Test void clientErrorsKeepShape() {
        assertEquals(400, errors.invalid(new IllegalArgumentException("x")).getStatusCode().value());
        assertEquals(409, errors.conflict().getStatusCode().value());
        assertEquals(404, errors.missing().getStatusCode().value());
    }
}

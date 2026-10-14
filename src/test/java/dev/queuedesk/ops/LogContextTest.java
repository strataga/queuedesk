package dev.queuedesk.ops;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MDC scoping without Spring: a null value removes the key instead of
 * inheriting a stale outer value, close restores the previous map, and odd
 * key/value counts are rejected.
 */
class LogContextTest {
    @Test void nullValueRemovesInsteadOfInheritingStaleOuter() {
        try (var outer = LogContext.of("eventId", "outer")) {
            assertEquals("outer", MDC.get("eventId"));
            try (var inner = LogContext.of("eventId", (String) null)) {
                assertNull(MDC.get("eventId"));
            }
            assertEquals("outer", MDC.get("eventId"));
        }
        assertNull(MDC.get("eventId"));
    }
    @Test void oddPairsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LogContext.of("lonely"));
    }
}

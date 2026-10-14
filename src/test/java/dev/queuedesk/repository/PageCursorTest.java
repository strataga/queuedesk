package dev.queuedesk.repository;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Opaque cursor encoding: round-trips exactly, blank means "from the
 * beginning", and garbage or half-valid tokens are rejected as bad cursors
 * (HTTP 400), never as server errors.
 */
class PageCursorTest {
    @Test void roundTrip() {
        var id = UUID.randomUUID();
        var ts = Instant.parse("2026-10-04T18:00:00Z");
        var decoded = PageCursor.decode(PageCursor.encode(ts, id)).orElseThrow();
        assertEquals(ts, decoded.createdAt());
        assertEquals(id, decoded.instructionId());
    }
    @Test void blankStartsAtBeginning() {
        assertTrue(PageCursor.decode(null).isEmpty());
        assertTrue(PageCursor.decode("  ").isEmpty());
    }
    @Test void garbageIsRejected() {
        assertThrows(PageCursor.BadCursor.class, () -> PageCursor.decode("!!!not-base64!!!"));
        String noBar = Base64.getUrlEncoder().withoutPadding().encodeToString("no-bar-here".getBytes());
        assertThrows(PageCursor.BadCursor.class, () -> PageCursor.decode(noBar));
    }
    @Test void malformedHalvesAreRejected() {
        // Instant.parse throws DateTimeParseException (not IllegalArgumentException);
        // both halves must still map to BadCursor (400), never escape as a 500.
        String badInstant = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("not-a-time|" + UUID.randomUUID()).getBytes());
        assertThrows(PageCursor.BadCursor.class, () -> PageCursor.decode(badInstant));
        String badId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026-10-04T18:00:00Z|not-a-uuid".getBytes());
        assertThrows(PageCursor.BadCursor.class, () -> PageCursor.decode(badId));
    }
}

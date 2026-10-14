package dev.queuedesk.repository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

// Opaque pagination cursor: base64url of "created_at|instruction_id". The pair is the
// deterministic tie-breaker for list ordering, so paging stays stable across inserts.
public record PageCursor(Instant createdAt, UUID instructionId) {
    public static class BadCursor extends RuntimeException {}
    public static Optional<PageCursor> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return Optional.empty();
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            if (bar < 0) throw new BadCursor();
            return Optional.of(new PageCursor(Instant.parse(raw.substring(0, bar)), UUID.fromString(raw.substring(bar + 1))));
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            // DateTimeException: Instant.parse fails with DateTimeParseException,
            // which is not an IllegalArgumentException — without this arm a
            // half-valid cursor escapes as a 500 instead of INVALID_CURSOR.
            throw new BadCursor();
        }
    }
    public static String encode(Instant createdAt, UUID instructionId) {
        String raw = createdAt.toString() + "|" + instructionId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}

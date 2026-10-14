package dev.queuedesk.repository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Opaque pagination cursor: base64url of {@code "created_at|instruction_id"}.
 *
 * <p>The pair is the deterministic tie-breaker for list ordering, so paging
 * stays stable while new rows are inserted (unlike offset paging, which
 * shifts under the reader). Clients treat the token as opaque and hand it
 * back verbatim; only this class knows the encoding.
 */
public record PageCursor(Instant createdAt, UUID instructionId) {
    /** The supplied cursor could not be decoded (HTTP 400, code {@code INVALID_CURSOR}). */
    public static class BadCursor extends RuntimeException {}

    /**
     * Decodes a client-supplied cursor. A {@code null} or blank value means
     * "start from the beginning" and decodes to {@link Optional#empty()}.
     *
     * @throws BadCursor when the token is present but malformed in any way
     */
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
    /** Encodes the last row of a page as the token for fetching the next one. */
    public static String encode(Instant createdAt, UUID instructionId) {
        String raw = createdAt.toString() + "|" + instructionId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}

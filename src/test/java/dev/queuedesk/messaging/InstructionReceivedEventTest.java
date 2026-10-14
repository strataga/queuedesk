package dev.queuedesk.messaging;

import tools.jackson.databind.ObjectMapper;
import dev.queuedesk.domain.PaymentInstruction;
import dev.queuedesk.domain.StoredInstruction;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

// Parse-boundary validation (no Spring): structural defects fail here with
// IllegalArgumentException so every caller shares one poison/digest path.
class InstructionReceivedEventTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private String validJson() {
        var payload = new PaymentInstruction("ref", "run", "SENDER_A", "RECEIVER_B", 1L, "USD");
        var stored = new StoredInstruction(UUID.randomUUID(), "k", "c", payload,
                Instant.parse("2026-10-05T00:00:00Z"), Instant.parse("2026-10-05T00:00:00Z"),
                "RECEIVED", null, 0);
        return InstructionReceivedEvent.of("evt-1", stored, Instant.parse("2026-10-05T00:00:00Z")).toJson(mapper);
    }
    @Test void roundTripPasses() {
        var envelope = InstructionReceivedEvent.fromJson(mapper, validJson());
        assertEquals("evt-1", envelope.eventId());
        assertEquals(InstructionReceivedEvent.SCHEMA_VERSION, envelope.schemaVersion());
    }
    @Test void syntacticGarbageIsRejectedButIdsStillExtract() {
        assertThrows(IllegalArgumentException.class,
                () -> InstructionReceivedEvent.fromJson(mapper, "{not json"));
        assertThrows(IllegalArgumentException.class,
                () -> InstructionReceivedEvent.fromJson(mapper, "null"));
        // Bad versions/types parse leniently WITH their IDs: the worker poison
        // path correlates on them (validatedPayload rejects the content next).
        // A strict parser here would anonymize poison and break ROUTED proof.
        String badVersion = validJson().replace("\"schemaVersion\":1", "\"schemaVersion\":99");
        assertEquals("evt-1", InstructionReceivedEvent.fromJson(mapper, badVersion).eventId());
    }
    @Test void backoffCurveIsPositiveBoundedAndCapped() {
        // The worker's pace and the publisher's defer share this curve: every
        // delivery must sleep a positive, bounded time (base plus <500ms jitter).
        for (int i = 1; i <= 5; i++) {
            long base = Math.min(1000L << i, 60_000L);
            for (int sample = 0; sample < 25; sample++) {
                long ms = OutboxPublisher.backoffMs(i);
                assertTrue(ms >= base && ms < base + 500, "out of band at " + i + ": " + ms);
            }
        }
        for (int sample = 0; sample < 25; sample++)
            assertTrue(OutboxPublisher.backoffMs(60) < 60_500, "cap exceeded");
    }
}

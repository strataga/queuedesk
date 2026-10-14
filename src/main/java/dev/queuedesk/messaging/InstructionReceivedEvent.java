package dev.queuedesk.messaging;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import dev.queuedesk.domain.PaymentInstruction;
import dev.queuedesk.domain.RequestHash;
import dev.queuedesk.domain.StoredInstruction;
import java.time.Instant;

/**
 * The versioned event envelope stored in the outbox and sent to MQ as a
 * UTF-8 JSON text message.
 *
 * <p>First-write wins: the {@code eventId} is allocated once at admission
 * and stays stable across every resend, which is what lets the worker dedup
 * safely. The payload repeats the business fields (plus the request hash)
 * so the worker can validate the message against the stored instruction
 * instead of trusting the wire.
 */
public record InstructionReceivedEvent(int schemaVersion, String eventType, String eventId,
        String instructionId, String correlationId, String runId, String occurredAt, Payload payload) {
    /** Business payload carried by the envelope, including the admission-time request hash. */
    public record Payload(String clientReference, String senderParticipant, String receiverParticipant,
            long amountMinor, String currency, String requestHash) {}
    /** Current envelope schema version. The worker rejects anything newer than it understands. */
    public static final int SCHEMA_VERSION = 1;
    /** The single event type this lab publishes. */
    public static final String TYPE = "InstructionReceived";

    /** Builds the envelope for a freshly admitted instruction. */
    public static InstructionReceivedEvent of(String eventId, StoredInstruction instruction, Instant occurredAt) {
        PaymentInstruction p = instruction.payload();
        return new InstructionReceivedEvent(SCHEMA_VERSION, TYPE, eventId,
                instruction.instructionId().toString(), instruction.correlationId(), p.runId(),
                occurredAt.toString(), new Payload(p.clientReference(), p.senderParticipant(),
                        p.receiverParticipant(), p.amountMinor(), p.currency(), RequestHash.of(p)));
    }
    /** Serializes the envelope to the exact JSON stored in the outbox and sent to MQ. */
    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (JacksonException impossible) {
            throw new IllegalStateException("Envelope serialization failed", impossible);
        }
    }
    /**
     * Parses an envelope from stored or received JSON. Syntax only; see the
     * warning inside before adding checks here.
     *
     * @throws IllegalArgumentException when the JSON cannot be read as an envelope at all
     */
    public static InstructionReceivedEvent fromJson(ObjectMapper mapper, String json) {
        // Syntactic parse ONLY — deliberately lenient. Structural validation
        // lives in ProcessingService.validatedPayload so the worker poison
        // path still sees the eventId/instructionId for correlation; throwing
        // here would anonymize bad-version envelopes (REGRESSION RISK: do not
        // add field checks without preserving ID extraction).
        try {
            var envelope = mapper.readValue(json, InstructionReceivedEvent.class);
            // Only the total-null case throws here: there are no IDs to
            // preserve, so anonymous poison handling is correct.
            if (envelope == null) throw new IllegalArgumentException("Null envelope");
            return envelope;
        } catch (JacksonException bad) {
            throw new IllegalArgumentException("Unparseable envelope", bad);
        }
    }
}

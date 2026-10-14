package dev.queuedesk.messaging;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import dev.queuedesk.domain.PaymentInstruction;
import dev.queuedesk.domain.RequestHash;
import dev.queuedesk.domain.StoredInstruction;
import java.time.Instant;

// Versioned FIRST_WRITE envelope (MSG-01). Serialized as UTF-8 JSON TextMessage;
// eventId is allocated once at admission and stays stable across every resend.
public record InstructionReceivedEvent(int schemaVersion, String eventType, String eventId,
        String instructionId, String correlationId, String runId, String occurredAt, Payload payload) {
    public record Payload(String clientReference, String senderParticipant, String receiverParticipant,
            long amountMinor, String currency, String requestHash) {}
    public static final int SCHEMA_VERSION = 1;
    public static final String TYPE = "InstructionReceived";
    public static InstructionReceivedEvent of(String eventId, StoredInstruction instruction, Instant occurredAt) {
        PaymentInstruction p = instruction.payload();
        return new InstructionReceivedEvent(SCHEMA_VERSION, TYPE, eventId,
                instruction.instructionId().toString(), instruction.correlationId(), p.runId(),
                occurredAt.toString(), new Payload(p.clientReference(), p.senderParticipant(),
                        p.receiverParticipant(), p.amountMinor(), p.currency(), RequestHash.of(p)));
    }
    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (JacksonException impossible) {
            throw new IllegalStateException("Envelope serialization failed", impossible);
        }
    }
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

package dev.queuedesk.domain;

import java.time.Instant;
import java.util.UUID;

// The durable shape of one admitted instruction: identity, keys, payload,
// business state and optimistic-locking version. Maps 1:1 to the instruction row.
public record StoredInstruction(UUID instructionId, String idempotencyKey, String correlationId,
        PaymentInstruction payload, Instant receivedAt, Instant updatedAt,
        String state, String reason, long version) {
    public String statusUrl() { return "/api/v1/instructions/" + instructionId; }
}

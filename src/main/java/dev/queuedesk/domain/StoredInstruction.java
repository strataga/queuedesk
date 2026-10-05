package dev.queuedesk.domain;

import java.time.Instant;
import java.util.UUID;

public record StoredInstruction(UUID instructionId, String idempotencyKey, String correlationId,
        PaymentInstruction payload, Instant receivedAt) {
    public String status() { return "RECEIVED"; }
    public String statusUrl() { return "/api/v1/instructions/" + instructionId; }
}

package dev.queuedesk.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The durable shape of one admitted instruction: identity, keys, payload,
 * business state, and optimistic-locking version. Maps 1:1 to the
 * {@code instruction} row in Postgres.
 *
 * <p>{@code version} is the optimistic-lock counter. Every state transition
 * writes with the version it read, so two writers racing the same payment
 * cannot both win: the loser sees a version mismatch and backs off.
 *
 * @param state current business state, one of {@link BusinessState}'s constants
 * @param reason why the state last changed (for example a risk reason code), may be {@code null}
 */
public record StoredInstruction(UUID instructionId, String idempotencyKey, String correlationId,
        PaymentInstruction payload, Instant receivedAt, Instant updatedAt,
        String state, String reason, long version) {
    /** Returns the public status URL clients poll after admission. */
    public String statusUrl() { return "/api/v1/instructions/" + instructionId; }
}

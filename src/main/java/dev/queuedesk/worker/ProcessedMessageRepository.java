package dev.queuedesk.worker;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The dedup key store: one row per logically handled {@code eventId}.
 *
 * <p>The row is inserted in the same database transaction as the business
 * outcome it records. That atomicity is the whole exactly-once-effect
 * story: a crash between the database commit and the JMS commit redelivers
 * an event whose key already exists, so the repeat is absorbed with no
 * second effect.
 */
public interface ProcessedMessageRepository {
    /** A handled event: which instruction it belonged to, when, and what the outcome was. */
    record Handled(UUID instructionId, Instant handledAt, String outcomeReference) {}

    /** Looks up whether an event was already handled. */
    Optional<Handled> find(String eventId);

    /** Records an event as handled. Must join the business outcome's transaction. */
    void insert(String eventId, UUID instructionId, String outcomeReference);

    /** Counts handled events for one instruction (evidence and test support). */
    long countForInstruction(UUID instructionId);
}

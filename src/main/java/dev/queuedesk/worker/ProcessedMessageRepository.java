package dev.queuedesk.worker;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

// Dedup key store: one row per logically handled eventId. The row is inserted in
// the SAME database transaction as the business outcome, so a crash between DB
// commit and JMS commit redelivers an event the key already absorbs (PR05).
public interface ProcessedMessageRepository {
    record Handled(UUID instructionId, Instant handledAt, String outcomeReference) {}
    Optional<Handled> find(String eventId);
    void insert(String eventId, UUID instructionId, String outcomeReference);
    long countForInstruction(UUID instructionId);
}

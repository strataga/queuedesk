package dev.queuedesk.worker;

import java.util.Optional;
import java.util.UUID;

/**
 * The receiver's durable truth: one row per instruction ID.
 *
 * <p>This row is written by the gateway in its own transaction, independent
 * of the worker's outcome transaction. That independence is what lets a
 * lost reply be reconciled later: the answer exists here even when the
 * caller never saw it. The {@code calls} counter is kept as evidence of
 * how often the receiver was asked.
 */
public interface ReceiverResultRepository {
    /** The stored receiver outcome ({@code COMPLETED}, {@code REJECTED}, {@code PENDING}, or {@code UNKNOWN}). */
    record Row(String outcome, String reason, int calls) {}

    /** Looks up the stored result for one instruction. */
    Optional<Row> find(UUID instructionId);

    /** Stores the first result for an instruction. Fails on a duplicate, like a real first-write race. */
    void insert(UUID instructionId, String outcome, String reason);

    /** Moves a pending marker to its definitive outcome and counts the call. */
    void complete(UUID instructionId, String outcome, String reason);

    /** Counts one more call against an unchanged stored result. */
    void touch(UUID instructionId);
}

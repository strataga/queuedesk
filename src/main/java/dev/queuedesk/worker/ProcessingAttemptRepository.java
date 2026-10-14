package dev.queuedesk.worker;

import java.util.List;
import java.util.UUID;

/**
 * Processing-attempt telemetry: observability, never business truth.
 *
 * <p>Rows written inside a rolled-back transaction vanish with it, by
 * design. Failure evidence that must survive a rollback is written
 * afterwards by a separate, controlled write in
 * {@link ProcessingService}. Nothing in the business flow reads these rows
 * to decide an outcome; they exist so humans (and the dashboard) can see
 * what happened.
 */
public interface ProcessingAttemptRepository {
    /** One recorded attempt: which event, what happened, and why. */
    record Attempt(String eventId, String result, String reason) {}

    /** Appends one attempt row in the caller's transaction. */
    void insert(UUID instructionId, String eventId, String result, String reason, Integer deliveryCount);

    /** Returns every recorded attempt for one instruction, in write order. */
    List<Attempt> forInstruction(UUID instructionId);

    /** Counts attempts with one result label for one instruction. */
    long countResult(UUID instructionId, String result);

    /** Delivery derivation for reads: which of these IDs have at least one FAILED or UNSUPPORTED attempt. */
    java.util.Set<UUID> withFailures(java.util.Collection<UUID> instructionIds);

    /** The latest failure evidence for one event, used to explain why it sits in quarantine. */
    record EventEvidence(UUID instructionId, String reason) {}

    /** Latest FAILED/UNSUPPORTED evidence for an event, if the worker ever saw it. */
    java.util.Optional<EventEvidence> latestForEvent(String eventId);
}

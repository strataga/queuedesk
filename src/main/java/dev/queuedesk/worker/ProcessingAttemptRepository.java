package dev.queuedesk.worker;

import java.util.List;
import java.util.UUID;

// Processing-attempt telemetry (DATA-02): observability, never business truth.
// Rows written inside a rolled-back transaction vanish; failure evidence that
// must survive rollback is written by a separate controlled write afterwards.
public interface ProcessingAttemptRepository {
    record Attempt(String eventId, String result, String reason) {}
    void insert(UUID instructionId, String eventId, String result, String reason, Integer deliveryCount);
    List<Attempt> forInstruction(UUID instructionId);
    long countResult(UUID instructionId, String result);
    // Delivery derivation: IDs with at least one FAILED or UNSUPPORTED attempt.
    java.util.Set<UUID> withFailures(java.util.Collection<UUID> instructionIds);
    // Reconciler: the latest evidence row for one eventId, if the worker saw it.
    record EventEvidence(UUID instructionId, String reason) {}
    java.util.Optional<EventEvidence> latestForEvent(String eventId);
}

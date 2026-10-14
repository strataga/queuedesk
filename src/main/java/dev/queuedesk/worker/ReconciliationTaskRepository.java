package dev.queuedesk.worker;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Durable reconciliation tasks (REC-02): one open task per uncertain
// instruction. Due/attempts persist so restart resumes and elapsed time alone
// never resolves anything.
public interface ReconciliationTaskRepository {
    record Task(long taskId, UUID instructionId, String eventId, int attempts,
            String traceparent, String tracestate) {}
    void create(UUID instructionId, String eventId, String traceparent, String tracestate);
    List<Task> claimDue(int limit);
    void reschedule(long taskId, int attempts, Instant nextDue, String error);
    void complete(long taskId, String error);
    Optional<Task> findOpen(UUID instructionId);
    long countOpen();
    long countOpenByRun(String runId);
}

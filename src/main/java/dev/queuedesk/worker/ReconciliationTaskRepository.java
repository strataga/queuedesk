package dev.queuedesk.worker;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable reconciliation tasks: one open task per instruction whose
 * receiver answer was lost or uncertain.
 *
 * <p>Due times and attempt counts persist, so a restart resumes where the
 * previous process left off. Elapsed time alone never resolves a task;
 * only definitive receiver evidence does. Tasks also carry the admission
 * trace context, so the eventual settle joins the payment's original trace.
 */
public interface ReconciliationTaskRepository {
    /** One open reconciliation task, with the trace context stored at defer time. */
    record Task(long taskId, UUID instructionId, String eventId, int attempts,
            String traceparent, String tracestate) {}

    /** Opens a task for an uncertain instruction. Must join the deferring transaction. */
    void create(UUID instructionId, String eventId, String traceparent, String tracestate);

    /**
     * Atomically claims due tasks for one poller pass. Claiming bumps each
     * task's due time as a short lease, so a crashed poller only delays a
     * task instead of losing it, and a second poller cannot double-claim.
     */
    List<Task> claimDue(int limit);

    /** Pushes a still-unresolved task to its next attempt time, recording the latest error. */
    void reschedule(long taskId, int attempts, Instant nextDue, String error);

    /** Closes a task. {@code error} records why when it closed without a settlement. */
    void complete(long taskId, String error);

    /** Finds the open task for one instruction, if any. */
    Optional<Task> findOpen(UUID instructionId);

    /** Counts open tasks lab-wide (the unresolved-work gauge). */
    long countOpen();

    /** Counts open tasks for one run. */
    long countOpenByRun(String runId);
}

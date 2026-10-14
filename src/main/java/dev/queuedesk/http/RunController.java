package dev.queuedesk.http;

import dev.queuedesk.domain.PaymentInstruction;
import dev.queuedesk.messaging.OutboxRepository;
import dev.queuedesk.repository.InstructionRepository;
import dev.queuedesk.worker.ReconciliationTaskRepository;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * Read-only run evidence: admitted counts by business state, plus outbox
 * and open-reconciliation counts for one generator run.
 *
 * <p>The scenario runner asserts outcomes through this endpoint, and the
 * dashboard reads it too. Unknown runs report zeros, never a 404: for an
 * evidence endpoint, the absence of evidence is itself evidence.
 */
@RestController
@RequestMapping("/api/v1/runs")
public class RunController {
    /** Run summary response: totals by state plus delivery and reconciliation gauges. */
    public record RunSummary(String runId, long admitted, Map<String, Long> byState,
            long outboxPending, long outboxPublished, long reconcileOpen) {}
    private final InstructionRepository instructions;
    private final OutboxRepository outbox;
    private final ReconciliationTaskRepository tasks;
    public RunController(InstructionRepository instructions, OutboxRepository outbox,
            ReconciliationTaskRepository tasks) {
        this.instructions = instructions;
        this.outbox = outbox;
        this.tasks = tasks;
    }
    @GetMapping("/{runId}")
    public RunSummary summary(@PathVariable String runId) {
        PaymentInstruction.token(runId, "runId");
        var byState = instructions.countByState(runId);
        var counts = outbox.countByRun(runId);
        return new RunSummary(runId, byState.values().stream().mapToLong(Long::longValue).sum(),
                byState, counts[0], counts[1], tasks.countOpenByRun(runId));
    }
}

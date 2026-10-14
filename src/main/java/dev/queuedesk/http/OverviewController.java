package dev.queuedesk.http;

import dev.queuedesk.messaging.OutboxRepository;
import dev.queuedesk.repository.InstructionRepository;
import dev.queuedesk.service.AdmissionService;
import dev.queuedesk.worker.ReconciliationTaskRepository;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

/**
 * Lab-wide overview reads for the dashboard's home page: a census, a
 * throughput history, and the most recent payments.
 *
 * <p>Instruction counts can be windowed by admission time (inclusive at
 * both ends, like the instruction list). Outbox and reconciliation figures
 * are current gauges and are never windowed: pending work exists now or it
 * does not. An empty lab reports zeros, never a 404. These endpoints are
 * read-only.
 */
@RestController
@RequestMapping("/api/v1/overview")
public class OverviewController {
    /** Census response: totals by business state plus current backlog gauges. */
    public record LabSummary(long admitted, Map<String, Long> byState,
            long outboxPending, long outboxPublished, long reconcileOpen,
            Instant since, Instant until) {}
    private final InstructionRepository instructions;
    private final OutboxRepository outbox;
    private final ReconciliationTaskRepository tasks;
    private final AdmissionService admission;
    public OverviewController(InstructionRepository instructions, OutboxRepository outbox,
            ReconciliationTaskRepository tasks, AdmissionService admission) {
        this.instructions = instructions;
        this.outbox = outbox;
        this.tasks = tasks;
        this.admission = admission;
    }
    @GetMapping
    public LabSummary summary(@RequestParam(required = false) Instant since,
            @RequestParam(required = false) Instant until) {
        if (since != null && until != null && since.isAfter(until))
            throw new IllegalArgumentException("since must not be after until");
        var byState = instructions.countLabByState(since, until);
        var counts = outbox.countLab();
        return new LabSummary(byState.values().stream().mapToLong(Long::longValue).sum(),
                byState, counts[0], counts[1], tasks.countOpen(), since, until);
    }

    /** One chart point: completions in the bucket starting at {@code at}. */
    public record ThroughputPoint(Instant at, long count) {}
    /** Throughput response: a contiguous, zero-filled series for direct charting. */
    public record Throughput(String metric, long bucketSeconds, java.util.List<ThroughputPoint> points,
            Instant since, Instant until) {}

    /**
     * Throughput history for the overview graph: durable per-bucket
     * completion counts, zero-filled across the window so the chart is
     * contiguous. {@code until} defaults to now; {@code since} defaults to
     * the first non-empty bucket, because an unbounded window cannot
     * zero-fill from minus infinity and leading zeros before the first
     * completion carry no signal. Windows over 2,000 buckets are refused.
     */
    @GetMapping("/throughput")
    public Throughput throughput(@RequestParam(required = false) Instant since,
            @RequestParam(required = false) Instant until,
            @RequestParam(defaultValue = "60") long bucketSeconds) {
        if (since != null && until != null && since.isAfter(until))
            throw new IllegalArgumentException("since must not be after until");
        if (bucketSeconds < 10 || bucketSeconds > 3600)
            throw new IllegalArgumentException("bucketSeconds must be 10..3600");
        var end = until != null ? until : Instant.now();
        var buckets = instructions.completionsPerBucket(since, end, bucketSeconds);
        var start = since != null ? since
                : buckets.keySet().stream().min(Instant::compareTo).orElse(end);
        var first = floorBucket(start, bucketSeconds);
        var last = floorBucket(end, bucketSeconds);
        long n = (last.getEpochSecond() - first.getEpochSecond()) / bucketSeconds + 1;
        if (n > 2000) throw new IllegalArgumentException(
                "window holds " + n + " buckets, over the 2000 cap: narrow since/until or widen bucketSeconds");
        var points = new java.util.ArrayList<ThroughputPoint>();
        for (var b = first; !b.isAfter(last); b = b.plusSeconds(bucketSeconds))
            points.add(new ThroughputPoint(b, buckets.getOrDefault(b, 0L)));
        return new Throughput("simulated_completions", bucketSeconds, java.util.List.copyOf(points),
                first, end);
    }
    private static Instant floorBucket(Instant at, long bucketSeconds) {
        return Instant.ofEpochSecond(at.getEpochSecond() / bucketSeconds * bucketSeconds);
    }

    /** Recent-payments response for the overview table. */
    public record RecentList(java.util.List<InstructionController.View> items) {}

    /**
     * The newest payments across the whole lab, newest first, in the same
     * view shape (with the same batched risk and delivery joins) as the
     * instruction list. First page only. {@code traceId} stays {@code null}
     * here, as in the list, to avoid an N+1 lookup; the detail view resolves
     * it for the Trace link.
     */
    @GetMapping("/recent")
    public RecentList recent(@RequestParam(defaultValue = "5") int limit) {
        var items = admission.recent(limit).stream().map(InstructionController.View::of).toList();
        return new RecentList(items);
    }
}

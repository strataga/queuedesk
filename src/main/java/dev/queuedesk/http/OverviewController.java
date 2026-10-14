package dev.queuedesk.http;

import dev.queuedesk.messaging.OutboxRepository;
import dev.queuedesk.repository.InstructionRepository;
import dev.queuedesk.service.AdmissionService;
import dev.queuedesk.worker.ReconciliationTaskRepository;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

// Lab-wide overview census (mockup 01): instruction counts over all runs,
// optionally windowed by admission time (inclusive both ends, like the list).
// Outbox and reconcile figures are CURRENT gauges, never windowed: pending
// work exists now or it does not. Unknown/empty labs report zeros, never 404.
@RestController
@RequestMapping("/api/v1/overview")
public class OverviewController {
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

    // Throughput history for the overview graph (mockup 01): durable
    // per-bucket completion counts, zero-filled across the window so the chart
    // is contiguous. until defaults to now; since defaults to the first
    // non-empty bucket (an unbounded window cannot zero-fill from -infinity,
    // and leading zeros before the first completion carry no signal).
    public record ThroughputPoint(Instant at, long count) {}
    public record Throughput(String metric, long bucketSeconds, java.util.List<ThroughputPoint> points,
            Instant since, Instant until) {}
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

    // Lab-wide recent payments for the overview table (mockup 01): newest
    // first, same View shape (and batched risk/delivery joins) as the list,
    // first page only. traceId stays null like the list (no N+1); the detail
    // view resolves it for the Trace link.
    public record RecentList(java.util.List<InstructionController.View> items) {}
    @GetMapping("/recent")
    public RecentList recent(@RequestParam(defaultValue = "5") int limit) {
        var items = admission.recent(limit).stream().map(InstructionController.View::of).toList();
        return new RecentList(items);
    }
}

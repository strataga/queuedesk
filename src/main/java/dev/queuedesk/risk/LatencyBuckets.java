package dev.queuedesk.risk;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Bounded in-memory latency history (DASH-02): per-minute buckets per metric,
// at most 60 buckets x 3 metrics. Micrometer retains no history server-side,
// so without this ring the UI could show only the current window — and the
// ring never backfills what it did not observe. Samples per minute are capped;
// drops stay visible in the dropped counter.
@Component
public class LatencyBuckets {
    public static final String ADMISSION = "admission";
    public static final String COMPLETION = "completion";
    public static final String RISK_PROVIDER = "riskProvider";
    private static final int MAX_BUCKETS = 60;
    private static final int MAX_SAMPLES_PER_MINUTE = 5000;

    public record Summary(double p95Ms, long n, long dropped) {}
    public record Point(Instant at, double p95Ms, long n) {}

    private record Bucket(long minuteEpoch, List<Long> samplesMs, long dropped) {}

    private final Map<String, Deque<Bucket>> rings = new HashMap<>();
    private long droppedTotal = 0;

    public synchronized void record(String metric, long latencyMs) {
        long minute = Instant.now().getEpochSecond() / 60;
        Deque<Bucket> ring = rings.computeIfAbsent(metric, m -> new ArrayDeque<>());
        Bucket head = ring.peekLast();
        if (head == null || head.minuteEpoch() != minute) {
            head = new Bucket(minute, new ArrayList<>(), 0);
            ring.addLast(head);
            while (ring.size() > MAX_BUCKETS) ring.removeFirst();
        }
        if (head.samplesMs().size() >= MAX_SAMPLES_PER_MINUTE) {
            ring.removeLast();
            ring.addLast(new Bucket(head.minuteEpoch(), head.samplesMs(), head.dropped() + 1));
            droppedTotal++;
        } else {
            head.samplesMs().add(latencyMs);
        }
    }

    public synchronized Summary summary(String metric, int windowMinutes) {
        long cutoff = Instant.now().getEpochSecond() / 60 - Math.max(1, windowMinutes) + 1;
        var all = new ArrayList<Long>();
        long dropped = 0;
        for (var bucket : rings.getOrDefault(metric, new ArrayDeque<>())) {
            if (bucket.minuteEpoch() < cutoff) continue;
            all.addAll(bucket.samplesMs());
            dropped += bucket.dropped();
        }
        return new Summary(percentile(all, 0.95), all.size(), dropped);
    }

    public synchronized List<Point> series(String metric, int windowMinutes) {
        long nowMinute = Instant.now().getEpochSecond() / 60;
        long cutoff = nowMinute - Math.min(windowMinutes, MAX_BUCKETS) + 1;
        var points = new ArrayList<Point>();
        for (var bucket : rings.getOrDefault(metric, new ArrayDeque<>())) {
            if (bucket.minuteEpoch() < cutoff) continue;
            var sorted = new ArrayList<>(bucket.samplesMs());
            sorted.sort(null);
            points.add(new Point(Instant.ofEpochSecond(bucket.minuteEpoch() * 60),
                    percentileSorted(sorted, 0.95), sorted.size()));
        }
        return points;
    }

    public synchronized void resetForTest() {
        rings.clear();
        droppedTotal = 0;
    }

    private static double percentile(List<Long> samples, double p) {
        if (samples.isEmpty()) return -1;
        var sorted = new ArrayList<>(samples);
        sorted.sort(null);
        return percentileSorted(sorted, p);
    }

    private static double percentileSorted(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return -1;
        int rank = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.min(Math.max(rank, 0), sorted.size() - 1));
    }
}

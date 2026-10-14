package dev.queuedesk.ops;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.stereotype.Component;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

/**
 * Bounded in-memory span ring: the last 1,000 finished spans, oldest
 * evicted first with a visible counter.
 *
 * <p>Synchronous by design (it runs on a {@code SimpleSpanProcessor}), so
 * {@code export} only appends: it never blocks, never throws into business
 * code, and never touches the network. The telemetry endpoints and tests
 * read finished spans from here. Sampled diagnostics only; this is not a
 * durable audit trail.
 */
@Component
public class SpanRingBuffer implements SpanExporter {
    private static final int CAPACITY = 1000;
    private final Deque<SpanData> ring = new ArrayDeque<>();
    private final TelemetryCounters counters;
    public SpanRingBuffer(TelemetryCounters counters) { this.counters = counters; }
    @Override
    public synchronized CompletableResultCode export(Collection<SpanData> spans) {
        try {
            for (var span : spans) {
                if (ring.size() >= CAPACITY) {
                    ring.removeFirst();
                    counters.bufferDropped();
                }
                ring.addLast(span);
            }
            return CompletableResultCode.ofSuccess();
        } catch (RuntimeException impossible) {
            return CompletableResultCode.ofFailure();
        }
    }
    /** Returns a snapshot copy of all retained spans, oldest first. */
    public synchronized List<SpanData> spans() { return new ArrayList<>(ring); }
    /** Returns retained spans with one exact name (trace assertions in tests). */
    public synchronized List<SpanData> byName(String name) {
        return ring.stream().filter(s -> s.getName().equals(name)).toList();
    }
    /** Test seam: clear the ring between tests sharing one context. */
    public synchronized void resetForTest() { ring.clear(); }
    @Override
    public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
    @Override
    public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
}

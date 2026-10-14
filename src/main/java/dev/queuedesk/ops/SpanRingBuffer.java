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

// Bounded in-memory span ring (TG02 evidence): the last 1000 finished spans,
// oldest evicted first with a visible counter. Synchronous by design (runs on
// a SimpleSpanProcessor), so export() only appends — it never blocks, never
// throws, and never touches the network. Tests read finished spans here.
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
    public synchronized List<SpanData> spans() { return new ArrayList<>(ring); }
    public synchronized List<SpanData> byName(String name) {
        return ring.stream().filter(s -> s.getName().equals(name)).toList();
    }
    public synchronized void resetForTest() { ring.clear(); }
    @Override
    public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
    @Override
    public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
}

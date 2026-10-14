package dev.queuedesk.ops;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Counting wrapper around the OTLP exporter: delegates every batch, counts
 * received, succeeded, and failed spans, and never throws.
 *
 * <p>A dead collector degrades to counted drops, never to backpressure into
 * admission or message acknowledgement. Recent batch outcomes (15 minutes)
 * feed the telemetry page's exporter-health view.
 */
public class CountingSpanExporter implements SpanExporter {
    private final SpanExporter delegate;
    private final TelemetryCounters counters;
    private final AtomicReference<String> lastError = new AtomicReference<>();
    // DASH-02 exporter health: last success time plus a bounded window of
    // batch outcomes (at most 15 minutes of per-second batches).
    private final AtomicReference<java.time.Instant> lastOkAt = new AtomicReference<>();
    private final java.util.Deque<BatchOutcome> recent = new java.util.ArrayDeque<>();
    private record BatchOutcome(long epochSecond, int spans, boolean ok) {}
    public CountingSpanExporter(SpanExporter delegate, TelemetryCounters counters) {
        this.delegate = delegate;
        this.counters = counters;
    }
    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        counters.exportReceived(spans.size());
        try {
            // Join bounded: the delegate future may never complete on timeout
            // paths (observed: whenComplete callbacks lost), so count from the
            // joined outcome instead. This blocks only the SDK batch worker —
            // never an admission or acknowledgement thread.
            var result = delegate.export(spans);
            result.join(10, java.util.concurrent.TimeUnit.SECONDS);
            if (result.isSuccess()) counters.exportOk(spans.size());
            else counters.exportFailed(spans.size());
            recordOutcome(spans.size(), result.isSuccess());
            return result;
        } catch (RuntimeException failed) {
            lastError.set(failed.toString());
            counters.exportFailed(spans.size());
            recordOutcome(spans.size(), false);
            return CompletableResultCode.ofFailure();
        }
    }
    private synchronized void recordOutcome(int spans, boolean ok) {
        if (ok) lastOkAt.set(java.time.Instant.now());
        recent.addLast(new BatchOutcome(java.time.Instant.now().getEpochSecond(), spans, ok));
        long cutoff = java.time.Instant.now().getEpochSecond() - 15 * 60;
        while (!recent.isEmpty() && recent.peekFirst().epochSecond() < cutoff) recent.removeFirst();
        while (recent.size() > 1024) recent.removeFirst();
    }
    /** The most recent delegate failure text, or {@code null} if none yet. */
    public String lastError() { return lastError.get(); }
    /** When a batch last exported successfully, or {@code null} if never. */
    public java.time.Instant lastOkAt() { return lastOkAt.get(); }

    /** Spans accepted in the trailing 15 minutes (the window is pruned on read). */
    public synchronized long spansSent15Min() {
        long cutoff = java.time.Instant.now().getEpochSecond() - 15 * 60;
        while (!recent.isEmpty() && recent.peekFirst().epochSecond() < cutoff) recent.removeFirst();
        return recent.stream().filter(BatchOutcome::ok).mapToLong(BatchOutcome::spans).sum();
    }
    /** Whether any export failed in the trailing 5 minutes (drives the DEGRADED status). */
    public synchronized boolean failedRecently() {
        long cutoff = java.time.Instant.now().getEpochSecond() - 5 * 60;
        return recent.stream().anyMatch(b -> b.epochSecond() >= cutoff && !b.ok());
    }
    @Override
    public CompletableResultCode flush() {
        try {
            return delegate.flush();
        } catch (RuntimeException failed) {
            return CompletableResultCode.ofFailure();
        }
    }
    @Override
    public CompletableResultCode shutdown() {
        try {
            return delegate.shutdown();
        } catch (RuntimeException failed) {
            return CompletableResultCode.ofFailure();
        }
    }
}

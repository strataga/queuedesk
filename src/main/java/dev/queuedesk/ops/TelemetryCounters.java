package dev.queuedesk.ops;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Telemetry self-accounting: every span the SDK starts, every span handed
 * to the OTLP exporter, every export success and failure, and every ring
 * eviction.
 *
 * <p>The gap between created and exporter-received counts is SDK-internal
 * buffering and drops, visible by comparison instead of silently lost.
 * That gap is the point: the telemetry pipeline reports on its own health
 * with the same honesty it asks of the business metrics.
 */
@Component
public class TelemetryCounters {
    private final Counter created;
    private final Counter exporterReceived;
    private final Counter exporterOk;
    private final Counter exporterFailed;
    private final Counter bufferDropped;
    public TelemetryCounters(MeterRegistry registry) {
        this.created = Counter.builder("queuedesk.telemetry.spans.created")
                .description("Spans started by the SDK").register(registry);
        this.exporterReceived = Counter.builder("queuedesk.telemetry.export.spans.received")
                .description("Spans handed to the OTLP exporter").register(registry);
        this.exporterOk = Counter.builder("queuedesk.telemetry.export.spans.ok")
                .description("Spans accepted by the OTLP endpoint").register(registry);
        this.exporterFailed = Counter.builder("queuedesk.telemetry.export.spans.failed")
                .description("Spans lost to exporter/backend failure").register(registry);
        this.bufferDropped = Counter.builder("queuedesk.telemetry.buffer.dropped")
                .description("Ring-buffer evictions (bounded diagnostics)").register(registry);
    }
    /** Counts one span started by the SDK. */
    public void spanCreated() { created.increment(); }
    /** Counts spans handed to the OTLP exporter. */
    public void exportReceived(int spans) { exporterReceived.increment(spans); }
    /** Counts spans the OTLP endpoint accepted. */
    public void exportOk(int spans) { exporterOk.increment(spans); }
    /** Counts spans lost to exporter or backend failure. */
    public void exportFailed(int spans) { exporterFailed.increment(spans); }
    /** Counts one bounded-ring eviction. */
    public void bufferDropped() { bufferDropped.increment(); }

    // Read-only totals for the telemetry page; the counters stay the only writers.
    /** Total spans started since process start. */
    public double createdCount() { return created.count(); }
    /** Total spans handed to the exporter since process start. */
    public double exporterReceivedCount() { return exporterReceived.count(); }
    /** Total spans accepted by the endpoint since process start. */
    public double exporterOkCount() { return exporterOk.count(); }
    /** Total spans lost to export failures since process start. */
    public double exporterFailedCount() { return exporterFailed.count(); }
    /** Total ring evictions since process start. */
    public double bufferDroppedCount() { return bufferDropped.count(); }
}

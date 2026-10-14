package dev.queuedesk.ops;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

// Telemetry counters (TEL-02): every span the SDK starts, every span handed
// to the OTLP exporter, every export failure, every ring eviction. The gap
// between created and exporter-received is SDK-internal buffering/drops —
// visible by comparison, never silent.
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
    public void spanCreated() { created.increment(); }
    public void exportReceived(int spans) { exporterReceived.increment(spans); }
    public void exportOk(int spans) { exporterOk.increment(spans); }
    public void exportFailed(int spans) { exporterFailed.increment(spans); }
    public void bufferDropped() { bufferDropped.increment(); }
    // Read-only totals for the telemetry page (DASH-02); counters stay the
    // only writers.
    public double createdCount() { return created.count(); }
    public double exporterReceivedCount() { return exporterReceived.count(); }
    public double exporterOkCount() { return exporterOk.count(); }
    public double exporterFailedCount() { return exporterFailed.count(); }
    public double bufferDroppedCount() { return bufferDropped.count(); }
}

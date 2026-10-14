package dev.queuedesk.ops;

import dev.queuedesk.risk.LatencyBuckets;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

// Single metrics owner (OBS-02): every queuedesk.* meter is built here. IDs
// (instruction/event/run) never become labels — fixed small tag sets only;
// IDs live in logs and traces. Gauges query the database live per scrape, so
// they always agree with durable state; -1 means "unknown", never zero. On a
// DB outage a gauge serves its last good reading (or -1 if none) instead of
// throwing — metrics must survive exactly when they are most needed.
@Component
public class QueuedeskMetrics {
    private final MeterRegistry registry;
    private final JdbcClient jdbc;
    private final LatencyBuckets buckets;
    private final java.util.concurrent.ConcurrentMap<String, Double> lastGood = new java.util.concurrent.ConcurrentHashMap<>();
    public QueuedeskMetrics(MeterRegistry registry, JdbcClient jdbc, LatencyBuckets buckets) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.buckets = buckets;
        Gauge.builder("queuedesk.outbox.pending", this, m -> m.outboxPending())
                .description("Accepted events not yet confirmed published").register(registry);
        Gauge.builder("queuedesk.outbox.oldest.age.seconds", this, m -> m.outboxOldestAge())
                .description("Age of the oldest unpublished outbox row; 0 when none").register(registry);
        Gauge.builder("queuedesk.pending.confirmation.count", this, m -> m.pendingCount())
                .description("Instructions awaiting receiver confirmation").register(registry);
        Gauge.builder("queuedesk.pending.confirmation.oldest.age.seconds", this, m -> m.pendingOldestAge())
                .description("Age of the longest-pending confirmation; 0 when none").register(registry);
        Gauge.builder("queuedesk.quarantine.count", this, m -> m.quarantineCount())
                .description("Held events in the quarantine projection").register(registry);
        Gauge.builder("queuedesk.quarantine.freshness.age.seconds", this, m -> m.quarantineFreshnessAge())
                .description("Age of the last quarantine reconcile; -1 when never").register(registry);
        Gauge.builder("queuedesk.mq.backlog.estimate.count", this, m -> m.mqBacklogEstimate())
                .description("APPLICATION ESTIMATE of published-but-unprocessed events (not a broker query)")
                .register(registry);
        Gauge.builder("queuedesk.mq.backlog.estimate.oldest.age.seconds", this, m -> m.mqBacklogOldestAge())
                .description("APPLICATION ESTIMATE of oldest unprocessed publish age; 0 when none").register(registry);
    }
    public void admission(String decision, long nanos) {
        Counter.builder("queuedesk.admissions.total").tag("decision", decision)
                .description("HTTP admission outcomes").register(registry).increment();
        Timer.builder("queuedesk.admission.duration").tag("decision", decision)
                .description("Request start to durable acceptance; errors are separate series")
                .register(registry).record(nanos, TimeUnit.NANOSECONDS);
        if ("accepted".equals(decision) || "repeated".equals(decision))
            buckets.record(LatencyBuckets.ADMISSION, TimeUnit.NANOSECONDS.toMillis(nanos));
    }
    public void outcome(String result, Instant receivedAt) {
        Counter.builder("queuedesk.outcomes.total").tag("result", result)
                .description("Durable terminal business outcomes").register(registry).increment();
        Timer.builder("queuedesk.endtoend.duration").tag("result", result)
                .description("Durable acceptance to durable terminal result; unresolved never recorded")
                .register(registry).record(Duration.between(receivedAt, Instant.now()));
        buckets.record(LatencyBuckets.COMPLETION, Duration.between(receivedAt, Instant.now()).toMillis());
    }
    // Risk ops (RISK-06): hold counts by policy result, provider calls by
    // status, provider latency distribution. Operational only — precision /
    // recall come from the labeled offline harness, never these meters.
    public void riskHold(String policyResult) {
        Counter.builder("queuedesk.risk.holds.total").tag("result", policyResult)
                .description("Risk gate holds by policy result").register(registry).increment();
    }
    public void riskProviderCall(String status, long latencyMs) {
        Counter.builder("queuedesk.risk.provider.calls.total").tag("status", status)
                .description("Risk provider calls by evaluation status").register(registry).increment();
        Timer.builder("queuedesk.risk.provider.duration").tag("status", status)
                .description("Risk provider call latency; faults are separate series")
                .register(registry).record(latencyMs, TimeUnit.MILLISECONDS);
        buckets.record(LatencyBuckets.RISK_PROVIDER, latencyMs);
    }
    public void dedup() {
        Counter.builder("queuedesk.dedup.total").description("Redeliveries absorbed by eventId dedup")
                .register(registry).increment();
    }
    public void failure(String result) {
        Counter.builder("queuedesk.processing.failed.total").tag("result", result)
                .description("Processing failure evidence by attempt result").register(registry).increment();
    }
    public void reconciled() {
        Counter.builder("queuedesk.reconciled.total").description("Tasks settled from receiver evidence")
                .register(registry).increment();
    }
    double queryOrLastGood(String gauge, java.util.function.DoubleSupplier query) {
        try {
            double value = query.getAsDouble();
            lastGood.put(gauge, value);
            return value;
        } catch (RuntimeException dbDown) {
            return lastGood.getOrDefault(gauge, -1.0);
        }
    }
    private double outboxPending() {
        return queryOrLastGood("outbox.pending",
                () -> jdbc.sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single());
    }
    private double outboxOldestAge() {
        return queryOrLastGood("outbox.oldest.age", () -> jdbc
                .sql("SELECT EXTRACT(EPOCH FROM (now() - MIN(created_at))) FROM outbox WHERE published_at IS NULL")
                .query(Double.class).optional().filter(a -> a != null).orElse(0.0));
    }
    private double pendingCount() {
        return queryOrLastGood("pending.count", () -> jdbc
                .sql("SELECT COUNT(*) FROM instruction WHERE state = 'PENDING_CONFIRMATION'").query(Long.class).single());
    }
    private double pendingOldestAge() {
        return queryOrLastGood("pending.oldest.age", () -> jdbc
                .sql("SELECT EXTRACT(EPOCH FROM (now() - MIN(updated_at))) FROM instruction WHERE state = 'PENDING_CONFIRMATION'")
                .query(Double.class).optional().filter(a -> a != null).orElse(0.0));
    }
    private double quarantineCount() {
        // Unresolved holds only: resolved rows linger for audit, not display.
        return queryOrLastGood("quarantine.count", () -> jdbc
                .sql("SELECT COUNT(*) FROM quarantine_record WHERE resolved_at IS NULL").query(Long.class).single());
    }
    private double quarantineFreshnessAge() {
        return queryOrLastGood("quarantine.freshness.age", () -> jdbc
                .sql("SELECT EXTRACT(EPOCH FROM (now() - last_reconciled_at)) FROM quarantine_projection_meta")
                .query(Double.class).optional().filter(a -> a != null).orElse(-1.0));
    }
    private double mqBacklogEstimate() {
        return queryOrLastGood("mq.backlog.estimate", () -> jdbc.sql("""
                SELECT COUNT(*) FROM outbox o WHERE o.published_at IS NOT NULL
                 AND NOT EXISTS (SELECT 1 FROM processed_message p WHERE p.event_id = o.event_id)
                 AND NOT EXISTS (SELECT 1 FROM processing_attempt a WHERE a.event_id = o.event_id AND a.result = 'ROUTED')""")
                .query(Long.class).single());
    }
    private double mqBacklogOldestAge() {
        return queryOrLastGood("mq.backlog.oldest.age", () -> jdbc.sql("""
                SELECT EXTRACT(EPOCH FROM (now() - MIN(o.published_at))) FROM outbox o WHERE o.published_at IS NOT NULL
                 AND NOT EXISTS (SELECT 1 FROM processed_message p WHERE p.event_id = o.event_id)
                 AND NOT EXISTS (SELECT 1 FROM processing_attempt a WHERE a.event_id = o.event_id AND a.result = 'ROUTED')""")
                .query(Double.class).optional().filter(a -> a != null).orElse(0.0));
    }
}

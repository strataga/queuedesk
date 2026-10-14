package dev.queuedesk.http;

import dev.queuedesk.ops.MqHealthIndicator;
import dev.queuedesk.worker.QuarantineRepository;
import dev.queuedesk.worker.WorkerListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * One read for the dashboard's Queues page: outbox backlog, an MQ depth
 * estimate, and quarantine freshness.
 *
 * <p>The backend has no broker admin probe, so MQ depth is honestly labeled
 * {@code APPLICATION_ESTIMATE}: it is derived from durable tables (published
 * events with no processed or routed record yet). Consumer state comes from
 * the local listener container, never from a broker query. When quarantine
 * data is stale, the live count is {@code null} and the last observed count
 * is kept. A fake zero would hide held poison messages.
 */
@RestController
@RequestMapping("/api/v1/ops/queues")
public class QueuesController {
    private final JdbcClient jdbc;
    private final MqHealthIndicator mqHealth;
    private final QuarantineRepository quarantine;
    private final JmsListenerEndpointRegistry registry;
    private final String queueManager;

    public QueuesController(JdbcClient jdbc, MqHealthIndicator mqHealth, QuarantineRepository quarantine,
            JmsListenerEndpointRegistry registry,
            @Value("${queuedesk.mq.queue-manager}") String queueManager) {
        this.jdbc = jdbc;
        this.mqHealth = mqHealth;
        this.quarantine = quarantine;
        this.registry = registry;
        this.queueManager = queueManager;
    }

    /** Outbox backlog: unpublished events, the age of the oldest, and the last successful publish. */
    public record OutboxView(long pending, double oldestPendingAgeSeconds, Instant lastPublishedAt,
            String dispatch, String lastEventId) {}
    /** MQ connectivity plus the application-side depth estimate and local consumer state. */
    public record MqView(boolean connected, String queueManager, long depthEstimate, String depthEstimateNote,
            double oldestEstimateAgeSeconds, int activeConsumers, String consumerState, Instant lastSendAt) {}
    /** Quarantine freshness: {@code count} is {@code null} when stale; {@code lastObserved} always keeps the last known value. */
    public record QuarantineView(Long count, long lastObserved, Instant lastObservedAt,
            double freshnessAgeSeconds, boolean stale) {}
    /** Combined queues response, stamped with the time it was assembled. */
    public record QueuesView(Instant asOf, OutboxView outbox, MqView mq, QuarantineView quarantine, long dedupLast15Min) {}

    /**
     * Returns the combined queues snapshot. {@code windowMinutes} (1–60)
     * bounds only the duplicate-deliveries-absorbed counter; the backlog
     * gauges are always current.
     */
    @GetMapping
    public QueuesView view(@RequestParam(defaultValue = "15") int windowMinutes) {
        if (windowMinutes < 1 || windowMinutes > 60)
            throw new IllegalArgumentException("windowMinutes must be 1..60");
        Instant asOf = Instant.now();
        long pending = jdbc.sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
        double oldestPending = jdbc.sql("SELECT EXTRACT(EPOCH FROM (now() - MIN(created_at)))"
                        + " FROM outbox WHERE published_at IS NULL")
                .query(Double.class).optional().filter(a -> a != null).orElse(0.0);
        Instant lastPublished = jdbc.sql("SELECT MAX(published_at) FROM outbox")
                .query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant).orElse(null);
        String lastEventId = jdbc.sql("SELECT event_id FROM outbox ORDER BY outbox_id DESC LIMIT 1")
                .query(String.class).optional().orElse(null);
        // Same application-estimate queries as QueuedeskMetrics (no broker probe).
        long depth = jdbc.sql("""
                SELECT COUNT(*) FROM outbox o WHERE o.published_at IS NOT NULL
                 AND NOT EXISTS (SELECT 1 FROM processed_message p WHERE p.event_id = o.event_id)
                 AND NOT EXISTS (SELECT 1 FROM processing_attempt a WHERE a.event_id = o.event_id AND a.result = 'ROUTED')""")
                .query(Long.class).single();
        double depthAge = jdbc.sql("""
                SELECT EXTRACT(EPOCH FROM (now() - MIN(o.published_at))) FROM outbox o WHERE o.published_at IS NOT NULL
                 AND NOT EXISTS (SELECT 1 FROM processed_message p WHERE p.event_id = o.event_id)
                 AND NOT EXISTS (SELECT 1 FROM processing_attempt a WHERE a.event_id = o.event_id AND a.result = 'ROUTED')""")
                .query(Double.class).optional().filter(a -> a != null).orElse(0.0);
        boolean connected;
        try {
            connected = "UP".equals(mqHealth.health().getStatus().getCode());
        } catch (RuntimeException probeFailed) {
            connected = false;
        }
        var container = registry.getListenerContainer(WorkerListener.LISTENER_ID);
        boolean running = container != null && container.isRunning();
        var heartbeat = quarantine.readHeartbeat();
        boolean stale = heartbeat.lastReconciledAt() == null
                || Duration.between(heartbeat.lastReconciledAt(), asOf).abs().toSeconds() > 60;
        long held = jdbc.sql("SELECT COUNT(*) FROM quarantine_record WHERE resolved_at IS NULL")
                .query(Long.class).single();
        double freshness = heartbeat.lastReconciledAt() == null ? -1.0
                : Duration.between(heartbeat.lastReconciledAt(), asOf).toMillis() / 1000.0;
        long dedup = jdbc.sql("SELECT COUNT(*) FROM processing_attempt WHERE result = 'DUPLICATE_SKIPPED'"
                        + " AND started_at >= ?")
                .param(OffsetDateTime.ofInstant(asOf.minusSeconds((long) windowMinutes * 60L),
                        java.time.ZoneOffset.UTC))
                .query(Long.class).single();
        return new QueuesView(asOf,
                new OutboxView(pending, oldestPending, lastPublished, "BOUNDED", lastEventId),
                new MqView(connected, queueManager, depth, "APPLICATION_ESTIMATE", depthAge,
                        running ? 1 : 0, running ? "LISTENING" : "STOPPED", lastPublished),
                new QuarantineView(stale ? null : held, held, heartbeat.lastReconciledAt(), freshness, stale),
                dedup);
    }
}

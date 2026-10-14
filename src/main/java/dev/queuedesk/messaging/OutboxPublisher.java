package dev.queuedesk.messaging;

import tools.jackson.databind.ObjectMapper;
import dev.queuedesk.fault.FaultLatches;
import dev.queuedesk.ops.LogContext;
import dev.queuedesk.ops.QueuedeskTracer;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The conveyor belt: polls the outbox and delivers each stored event to MQ
 * exactly as recorded.
 *
 * <p>Due rows are sent in ID order, one persistent JMS message per row, and
 * the published marker is written afterwards in its own database
 * transaction. That order matters. If the process dies between the send and
 * the marker, recovery simply resends the same event with the same
 * {@code eventId}, and the worker dedups it. Nothing is ever deleted to
 * "catch up".
 *
 * <p>A failed row stops the batch (preserving order), records the failure,
 * and backs off with a capped, jittered delay. While MQ is down, admissions
 * keep returning 202 and the backlog drains on recovery.
 */
@Component
public class OutboxPublisher {
    /**
     * Writes the published marker after a confirmed send. Kept as an
     * interface so tests can fault the marker write and prove the
     * crash-after-commit recovery path.
     */
    public interface MarkerWriter { void markPublished(OutboxRepository.DueRow row); }
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepository outbox;
    private final JmsTemplate jms;
    private final ObjectMapper mapper;
    private final MarkerWriter marker;
    private final FaultLatches faults;
    private final QueuedeskTracer tracing;
    private final String queueIn;
    private final boolean enabled;
    private final int batchSize;
    private final AtomicLong publishedTotal = new AtomicLong();
    private final AtomicLong failedTotal = new AtomicLong();
    private final AtomicLong consecutiveFailures = new AtomicLong();
    private final AtomicBoolean backlogAlerted = new AtomicBoolean();
    private final long backlogLimit;
    private volatile long resumeAtEpochMs;
    public OutboxPublisher(OutboxRepository outbox, JmsTemplate mqJmsTemplate, ObjectMapper mapper,
            MarkerWriter marker, FaultLatches faults, QueuedeskTracer tracing,
            @Value("${queuedesk.mq.queue-in}") String queueIn,
            @Value("${queuedesk.publisher.enabled:true}") boolean enabled,
            @Value("${queuedesk.publisher.batch-size:10}") int batchSize,
            @Value("${queuedesk.guards.backlog-limit:1000}") long backlogLimit) {
        this.outbox = outbox;
        this.jms = mqJmsTemplate;
        this.mapper = mapper;
        this.marker = marker;
        this.faults = faults;
        this.tracing = tracing;
        this.backlogLimit = backlogLimit;
        this.queueIn = queueIn;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }
    /** Scheduled entry point: one batch per poll while the publisher is enabled. */
    @Scheduled(fixedDelayString = "${queuedesk.publisher.poll-delay-ms:1000}")
    public void poll() { if (enabled) publishBatch(); }
    // Test seam: the Spring context (and this singleton) is shared across tests,
    // so each test starts with a clean backoff window. Public because the worker
    // tests drive the publisher from another package.
    public void resetBackoffForTest() { resumeAtEpochMs = 0; }
    /**
     * Sends one due batch in order.
     *
     * @return how many events were sent and marked published; 0 while a
     *         backoff window from an earlier failure is still open
     */
    public int publishBatch() {
        if (System.currentTimeMillis() < resumeAtEpochMs) return 0;
        var due = outbox.dueBatch(batchSize);
        int sent = 0;
        for (var row : due) {
            try {
                var envelope = InstructionReceivedEvent.fromJson(mapper, row.payload());
                try (var scope = LogContext.of("stage", "publish", "eventId", row.eventId(),
                        "instructionId", envelope.instructionId(), "runId", envelope.runId(),
                        "correlationId", envelope.correlationId())) {
                    sendOne(row, envelope);
                }
                sent++;
                publishedTotal.incrementAndGet();
                consecutiveFailures.set(0);
            } catch (RuntimeException failed) {
                failedTotal.incrementAndGet();
                int attempts = row.attempts() + 1;
                outbox.deferOnFailure(row.outboxId(), attempts, Instant.now().plusMillis(backoffMs(attempts)),
                        failed.toString());
                long sleepMs = backoffMs((int) consecutiveFailures.incrementAndGet());
                resumeAtEpochMs = System.currentTimeMillis() + sleepMs;
                log.warn("outbox.publish.failed eventId={} attempts={} resumeInMs={} error={}",
                        row.eventId(), attempts, sleepMs, failed.toString());
                break;
            }
        }
        if (sent > 0 || !due.isEmpty()) {
            long pending = outbox.countPending();
            log.info("outbox.batch sent={} due={} pending={} oldestAgeMs={} publishedTotal={} failedTotal={}",
                    sent, due.size(), pending, outbox.oldestPendingAgeMs(),
                    publishedTotal.get(), failedTotal.get());
            // SAFE-01 backlog alert, edge-triggered: warn once on crossing, not
            // every poll. The publisher keeps draining; the generator halts new
            // traffic. Never clear pending work to satisfy the guard.
            if (pending > backlogLimit && backlogAlerted.compareAndSet(false, true))
                log.warn("guard.backlog.exceeded pending={} limit={} (generator halts new traffic; drain, never clear)", pending, backlogLimit);
            else if (pending <= backlogLimit) backlogAlerted.set(false);
        }
        return sent;
    }
    // One row: persistent send, then the published marker. Runs inside the
    // publish MDC scope; failures propagate to the batch's defer path. The
    // PRODUCER span joins the admission trace via the stored row context, and
    // its own context rides the JMS properties to the consumer.
    private void sendOne(OutboxRepository.DueRow row, InstructionReceivedEvent envelope) {
        try (var span = tracing.child("queuedesk.outbox.publish",
                io.opentelemetry.api.trace.SpanKind.PRODUCER, row.traceparent(), row.tracestate(),
                java.util.Map.of("messaging.event.id", row.eventId(),
                        "business.instruction.id", envelope.instructionId(),
                        "business.run.id", envelope.runId()))) {
            jms.send(queueIn, session -> {
                TextMessage message = session.createTextMessage(row.payload());
                message.setJMSCorrelationID(envelope.correlationId());
                message.setStringProperty("eventId", row.eventId());
                message.setStringProperty("runId", envelope.runId());
                String traceparent = QueuedeskTracer.currentTraceparent();
                if (traceparent != null) message.setStringProperty("traceparent", traceparent);
                return message;
            });
            // Crash window (FAIL-01): the transacted send committed when
            // jms.send returned; the published marker is not yet written.
            // A halt here must resend the SAME eventId after restart.
            faults.tripPublisherAfterCommit();
            marker.markPublished(row);
            LogContext.set("outcome", "published");
            log.info("outbox.sent eventId={}", row.eventId());
        }
    }
    /**
     * Capped exponential backoff with jitter, shared with the worker's
     * transient-failure pacing so both sides recover at the same rhythm.
     */
    public static long backoffMs(int failures) {
        long exponential = 1000L << Math.min(failures, 6);
        return Math.min(exponential, 60_000L) + ThreadLocalRandom.current().nextLong(0, 500);
    }
}

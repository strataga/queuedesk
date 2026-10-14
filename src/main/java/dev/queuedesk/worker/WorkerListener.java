package dev.queuedesk.worker;

import tools.jackson.databind.ObjectMapper;
import dev.queuedesk.fault.FaultLatches;
import dev.queuedesk.messaging.InstructionReceivedEvent;
import dev.queuedesk.messaging.OutboxPublisher;
import dev.queuedesk.ops.LogContext;
import dev.queuedesk.ops.QueuedeskTracer;
import dev.queuedesk.risk.RiskGateService;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.stereotype.Component;
import java.util.UUID;

// The single worker consumer (WORK-01, PR06): one message at a time under a
// LOCAL JMS transaction. Poison routing is APPLICATION-owned (QUAR-01): the
// listener counts JMSXDeliveryCount and, after `backoutThreshold` failed
// processing deliveries, moves the message to the backout queue in the SAME
// transacted session and commits, so the move is atomic. A failed move stops
// the consumer with the message retained — never a hot loop, never a loss.
// Transient failures pace with backoff OUTSIDE the business transaction, so a
// DB blip does not burn all three deliveries in milliseconds.
@Component
public class WorkerListener {
    public static final String LISTENER_ID = "queuedesk-worker";
    private static final Logger log = LoggerFactory.getLogger(WorkerListener.class);
    private final ProcessingService processing;
    private final RiskGateService riskGate;
    private final ObjectMapper mapper;
    private final JmsListenerEndpointRegistry registry;
    private final FaultLatches faults;
    private final QueuedeskTracer tracing;
    private volatile String queueBackout;
    private final int backoutThreshold;
    public WorkerListener(ProcessingService processing, RiskGateService riskGate, ObjectMapper mapper,
            JmsListenerEndpointRegistry registry, FaultLatches faults, QueuedeskTracer tracing,
            @Value("${queuedesk.mq.queue-backout}") String queueBackout,
            @Value("${queuedesk.worker.backout-threshold:3}") int backoutThreshold) {
        this.processing = processing;
        this.riskGate = riskGate;
        this.mapper = mapper;
        this.registry = registry;
        this.faults = faults;
        this.tracing = tracing;
        this.queueBackout = queueBackout;
        this.backoutThreshold = backoutThreshold;
    }
    // Test seam: redirect routing at a nonexistent queue to prove the
    // route-failure backstop without racing MQ object lifecycle.
    void setQueueBackoutForTest(String queueBackout) { this.queueBackout = queueBackout; }
    @JmsListener(id = LISTENER_ID, destination = "${queuedesk.mq.queue-in}",
            containerFactory = "workerListenerFactory")
    public void onMessage(Message message, Session session) throws Exception {
        String jmsId = message.getJMSMessageID();
        int deliveryCount = deliveryCountOf(message, jmsId);
        if (!(message instanceof TextMessage text)) {
            failPoison(session, message, null, null, jmsId, deliveryCount,
                    "non-text message; Java object messages are never deserialized");
            return;
        }
        final InstructionReceivedEvent envelope;
        try {
            envelope = InstructionReceivedEvent.fromJson(mapper, text.getText());
        } catch (IllegalArgumentException bad) {
            failPoison(session, message, null, null, jmsId, deliveryCount,
                    "unparseable envelope: " + bad.getMessage());
            return;
        }
        UUID instructionId = bestEffortInstructionId(envelope);
        String eventId = envelope.eventId();
        // JMSMessageID is transport identity only: it can change on resend, so the
        // dedup key is always the stable business eventId (LEARN-02).
        // Each delivery gets its own CONSUMER span, child of the JMS-carried
        // producer context: redeliveries are siblings tagged with their
        // deliveryAttempt, never a reopened span. Tools that send without
        // trace properties (poison/redeliver drills) start honest new traces.
        try (var span = tracing.child("queuedesk.worker.attempt",
                io.opentelemetry.api.trace.SpanKind.CONSUMER,
                stringProperty(message, "traceparent"), stringProperty(message, "tracestate"),
                java.util.Map.of("messaging.event.id", String.valueOf(eventId),
                        "business.instruction.id", String.valueOf(envelope.instructionId()),
                        "messaging.delivery.attempt", String.valueOf(deliveryCount)))) {
            try (var scope = LogContext.of("stage", "consume", "eventId", eventId,
                    "instructionId", envelope.instructionId(), "runId", envelope.runId(),
                    "deliveryAttempt", String.valueOf(deliveryCount),
                    "correlationId", envelope.correlationId())) {
                handle(envelope, session, message, instructionId, eventId, jmsId, deliveryCount);
            }
        }
    }
    private static String stringProperty(Message message, String name) {
        try {
            return message.propertyExists(name) ? message.getStringProperty(name) : null;
        } catch (jakarta.jms.JMSException unreadable) {
            return null;
        }
    }
    private void handle(InstructionReceivedEvent envelope, Session session, Message message,
            UUID instructionId, String eventId, String jmsId, int deliveryCount) throws Exception {
        log.info("worker.received eventId={} jmsId={} instructionId={} delivery={}",
                eventId, jmsId, envelope.instructionId(), deliveryCount);
        // Crash window 1 (FAIL-01): before any DB write — rollback is total.
        faults.tripWorkerBeforeCommit();
        try {
            // Pre-clearing risk gate (RISK-04): a verdict here already
            // committed (hold/reject); empty means ALLOW — fall through to
            // the unchanged process path. OFF mode short-circuits inside.
            var result = riskGate.evaluate(envelope, deliveryCount)
                    .orElseGet(() -> processing.process(envelope, deliveryCount));
            // Crash window 2 (FAIL-01): the DB transaction committed inside
            // process(); the JMS session commits only after this method
            // returns. A halt here proves redelivery-meets-dedup for real.
            faults.tripWorkerAfterCommit();
            LogContext.set("outcome", result.outcome());
            LogContext.set("reason", result.reason());
            log.info("worker.handled eventId={} outcome={} state={}", eventId, result.outcome(), result.state());
        } catch (ProcessingService.UnsupportedEnvelope unsupported) {
            failPoison(session, message, instructionId, eventId, jmsId, deliveryCount, unsupported.getMessage());
        } catch (RuntimeException failed) {
            // Evidence is best-effort: a DB outage here must not skip the pace
            // below, or all three deliveries burn in milliseconds.
            try {
                processing.recordFailureAfterRollback(instructionId, eventId, failed.toString(), deliveryCount);
            } catch (RuntimeException dbDown) {
                log.warn("worker.failed.evidence.lost eventId={} error={}", eventId, dbDown.toString());
            }
            if (deliveryCount >= backoutThreshold) {
                routeToBackout(session, message, instructionId, eventId, deliveryCount,
                        "transient failure on delivery " + deliveryCount + ": " + failed);
                return;
            }
            pace(deliveryCount, failed);
            log.warn("worker.failed eventId={} delivery={} error={} (paced; delivery rolls back for redelivery)",
                    eventId, deliveryCount, failed.toString());
            throw failed;
        }
    }
    // One poison failure: evidence first, then route on the threshold delivery,
    // else roll back for another attempt. Returns normally after routing (the
    // session commit performs the atomic move); otherwise always throws.
    private void failPoison(Session session, Message message, UUID instructionId, String eventId,
            String jmsId, int deliveryCount, String reason) throws Exception {
        // Best-effort like the transient path: lost evidence must not skip
        // the pace before the rollback below.
        try {
            processing.recordUnsupportedAfterRollback(instructionId, eventId, reason, deliveryCount);
        } catch (RuntimeException dbDown) {
            log.warn("worker.poison.evidence.lost eventId={} error={}", eventId, dbDown.toString());
        }
        if (deliveryCount >= backoutThreshold) {
            routeToBackout(session, message, instructionId, eventId, deliveryCount, reason);
            return;
        }
        // Paced like the transient path: without this, sub-threshold poison
        // redeliveries spin hot instead of backing off toward the route.
        pace(deliveryCount, null);
        log.warn("worker.poison eventId={} delivery={} reason={} (paced; rolls back; routes after delivery {})",
                eventId, deliveryCount, reason, backoutThreshold);
        throw new IllegalStateException("unsupported envelope " + eventId + ": " + reason);
    }
    private void routeToBackout(Session session, Message message, UUID instructionId, String eventId,
            int deliveryCount, String reason) throws Exception {
        pace(deliveryCount, null);
        try {
            var producer = session.createProducer(session.createQueue(queueBackout));
            try {
                producer.send(message);
            } finally {
                try {
                    producer.close();
                } catch (jakarta.jms.JMSException closeFailed) {
                    // The send is what matters; the session commit arbitrates.
                    log.warn("worker.route.producer.close eventId={} error={}", eventId, closeFailed.toString());
                }
            }
        } catch (Exception routeFailed) {
            // The move itself failed (queue missing, no rights, depth): evidence
            // best-effort, then halt with the message retained. Retrying a broken
            // route would only spin; the operator repairs routing and restarts.
            try {
                processing.recordRouteFailed(instructionId, eventId, "route-failed: " + routeFailed, deliveryCount);
            } catch (RuntimeException dbDown) {
                log.warn("worker.route.evidence.lost eventId={} error={}", eventId, dbDown.toString());
            }
            stopWith("route to " + queueBackout + " failed: " + routeFailed, eventId, deliveryCount);
            throw new IllegalStateException("route to backout failed for " + eventId, routeFailed);
        }
        processing.recordRouted(instructionId, eventId, reason, deliveryCount);
        log.warn("worker.routed eventId={} delivery={} reason={} (moved to {}; session commit finalizes)",
                eventId, deliveryCount, reason, queueBackout);
    }
    private void stopWith(String reason, String eventId, int deliveryCount) {
        var container = registry.getListenerContainer(LISTENER_ID);
        if (container != null && container.isRunning()) container.stop();
        log.error("worker.stopped reason={} eventId={} delivery={} (repair routing, then restart the consumer; nothing was discarded)",
                reason, eventId, deliveryCount);
    }
    // Pacing lives OUTSIDE the business transaction (already rolled back): a DB
    // blip must not burn all three deliveries in milliseconds, and a route that
    // cannot record (DB down) must spin paced, not hot.
    private static void pace(int deliveryCount, RuntimeException failed) {
        try {
            Thread.sleep(OutboxPublisher.backoffMs(Math.max(1, deliveryCount)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (failed != null) throw failed;
            throw new IllegalStateException("interrupted while pacing quarantine route");
        }
    }
    private static int deliveryCountOf(Message message, String jmsId) {
        try {
            if (message.propertyExists("JMSXDeliveryCount")) return message.getIntProperty("JMSXDeliveryCount");
        } catch (jakarta.jms.JMSException unreadable) {
            log.warn("worker.delivery.unknown jmsId={} error={} (counting from 1; app routing still bounds poison)",
                    jmsId, unreadable.toString());
            return 1;
        }
        log.warn("worker.delivery.unknown jmsId={} (counting from 1; app routing still bounds poison)", jmsId);
        return 1;
    }
    private static UUID bestEffortInstructionId(InstructionReceivedEvent envelope) {
        try {
            return envelope.instructionId() == null ? null : UUID.fromString(envelope.instructionId());
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }
}

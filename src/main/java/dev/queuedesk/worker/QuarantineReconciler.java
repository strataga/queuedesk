package dev.queuedesk.worker;

import tools.jackson.databind.ObjectMapper;
import dev.queuedesk.messaging.InstructionReceivedEvent;
import jakarta.jms.BytesMessage;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.UUID;

// QUAR-02: reconciles the backout queue into quarantine_record rows by BROWSING
// only — observing held work must never consume it. The projection lags the MQ
// move by design; the heartbeat keeps that lag visible instead of pretending
// the move and the projection are atomic. Failures (broker/DB down) are logged
// and retried next pass; a pass never throws into the scheduler.
@Component
public class QuarantineReconciler {
    static final String UNPARSEABLE = "UNPARSEABLE_ENVELOPE";
    private static final Logger log = LoggerFactory.getLogger(QuarantineReconciler.class);
    private final QuarantineRepository quarantine;
    private final ProcessingAttemptRepository attempts;
    private final JmsTemplate jms;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final String queueBackout;
    private final boolean enabled;
    public QuarantineReconciler(QuarantineRepository quarantine, ProcessingAttemptRepository attempts,
            JmsTemplate jms, ObjectMapper mapper, PlatformTransactionManager txManager,
            @Value("${queuedesk.mq.queue-backout}") String queueBackout,
            @Value("${queuedesk.quarantine.enabled:true}") boolean enabled) {
        this.quarantine = quarantine;
        this.attempts = attempts;
        this.jms = jms;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
        this.queueBackout = queueBackout;
        this.enabled = enabled;
    }
    @Scheduled(fixedDelayString = "${queuedesk.quarantine.poll-delay-ms:15000}")
    public void poll() { if (enabled) reconcile(); }
    public void reconcile() {
        try (var scope = dev.queuedesk.ops.LogContext.of("stage", "quarantine")) {
            reconcileHeld();
        }
    }
    // Browse cap: a pass never materializes more than this many held
    // messages. Resolution runs only on complete browses — resolving from a
    // truncated view would wrongly close holds past the cap.
    static final int MAX_BROWSE = 1000;
    private void reconcileHeld() {
        final Browse browse;
        try {
            browse = browseHeld();
        } catch (RuntimeException brokerDown) {
            log.warn("quarantine.reconcile.broker.unreachable error={}", brokerDown.toString());
            heartbeatQuietly(brokerDown.toString());
            return;
        }
        var held = browse.held();
        try {
            tx.execute(s -> {
                for (var h : held) quarantine.upsert(h.digest(), h.eventId(), h.instructionId(), h.reason());
                if (!browse.truncated()) {
                    var digests = new java.util.HashSet<String>();
                    for (var h : held) digests.add(h.digest());
                    quarantine.resolveExcept(digests);
                }
                quarantine.heartbeat(browse.truncated() ? "browse truncated at " + MAX_BROWSE : null);
                return null;
            });
            if (!held.isEmpty() || browse.truncated())
                log.info("quarantine.reconciled held={} truncated={}", held.size(), browse.truncated());
        } catch (RuntimeException dbDown) {
            log.warn("quarantine.reconcile.db.unreachable held={} error={}", held.size(), dbDown.toString());
            heartbeatQuietly(dbDown.toString());
        }
    }
    private void heartbeatQuietly(String error) {
        try {
            tx.execute(s -> { quarantine.heartbeat(error); return null; });
        } catch (RuntimeException stillDown) {
            log.warn("quarantine.heartbeat.lost error={}", stillDown.toString());
        }
    }
    private record Held(String digest, String eventId, UUID instructionId, String reason) {}
    private record Browse(ArrayList<Held> held, boolean truncated) {}
    private Browse browseHeld() {
        return jms.browse(queueBackout, (session, browser) -> {
            var held = new ArrayList<Held>();
            var messages = browser.getEnumeration();
            while (messages.hasMoreElements() && held.size() < MAX_BROWSE)
                held.add(classify((Message) messages.nextElement()));
            return new Browse(held, messages.hasMoreElements());
        });
    }
    private Held classify(Message message) throws jakarta.jms.JMSException {
        byte[] body;
        if (message instanceof TextMessage text) body = text.getText().getBytes(StandardCharsets.UTF_8);
        else if (message instanceof BytesMessage bytes) {
            body = new byte[(int) Math.min(bytes.getBodyLength(), 1_000_000)];
            bytes.readBytes(body);
        } else body = ("jms-type:" + message.getClass().getSimpleName()).getBytes(StandardCharsets.UTF_8);
        final String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        String eventId = null;
        UUID instructionId = null;
        String reason = UNPARSEABLE;
        if (message instanceof TextMessage text) {
            try {
                var envelope = InstructionReceivedEvent.fromJson(mapper, text.getText());
                eventId = envelope.eventId();
                try { instructionId = UUID.fromString(envelope.instructionId()); } catch (IllegalArgumentException bad) { /* stays null */ }
            } catch (IllegalArgumentException unparseable) { /* digest-only identity */ }
        }
        // The worker's attempt rows hold the exact failure reason; without an
        // eventId there is nothing to join, so the stable generic stands.
        if (eventId != null) {
            var evidence = attempts.latestForEvent(eventId);
            if (evidence.isPresent()) {
                reason = evidence.get().reason();
                if (instructionId == null) instructionId = evidence.get().instructionId();
            } else reason = "UNSUPPORTED (reason pending)";
        }
        return new Held(digest, eventId, instructionId, reason);
    }
}

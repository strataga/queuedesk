package dev.queuedesk.messaging;

import tools.jackson.databind.ObjectMapper;
import dev.queuedesk.domain.PaymentInstruction;
import dev.queuedesk.domain.RequestHash;
import dev.queuedesk.domain.StoredInstruction;
import dev.queuedesk.repository.InstructionRepository;
import dev.queuedesk.service.AdmissionService;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

// Outbox + publisher proofs against real Postgres and real MQ.
// Needs ./scripts/postgres up and ./scripts/mq up; fails loudly without them.
// No background schedulers: cached test contexts must not run pollers that
// steal fellow suites' work (WorkerTest's tasks were once skipped mid-test
// by a stale scheduled poller holding the claim lock).
// OPS-02: integration suite — needs the named lab PG + MQ; CI runs it explicitly.
@Tag("integration")
// outageSurvival owns the shared broker mid-suite: never run concurrently
// with other tests, even if parallelism is enabled later.
@Isolated
@SpringBootTest(properties = {"queuedesk.publisher.enabled=false", "queuedesk.worker.enabled=false",
        "queuedesk.reconcile.enabled=false", "queuedesk.quarantine.enabled=false"})
class OutboxPublisherTest {
    // Controllable marker: faults once when armed, then delegates to the database.
    static class FaultOnceMarker implements OutboxPublisher.MarkerWriter {
        private final OutboxPublisher.MarkerWriter delegate;
        private final AtomicBoolean armed = new AtomicBoolean();
        FaultOnceMarker(OutboxPublisher.MarkerWriter delegate) { this.delegate = delegate; }
        void arm() { armed.set(true); }
        @Override
        public void markPublished(OutboxRepository.DueRow row) {
            if (armed.compareAndSet(true, false)) throw new IllegalStateException("injected marker fault");
            delegate.markPublished(row);
        }
    }
    @TestConfiguration
    static class MarkerConfig {
        @Bean
        @Primary
        FaultOnceMarker faultingMarker(DbMarkerWriter delegate) { return new FaultOnceMarker(delegate); }
    }
    @Autowired AdmissionService service;
    @Autowired InstructionRepository instructions;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxPublisher publisher;
    @Autowired dev.queuedesk.fault.FaultLatches faults;
    @Autowired FaultOnceMarker marker;
    @Autowired JmsTemplate jms;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Value("${queuedesk.mq.queue-in}") String queueIn;
    private String run;
    @BeforeEach void freshRun() { run = "mq-" + UUID.randomUUID(); publisher.resetBackoffForTest(); faults.resetForTest(); }
    // Recovery never happens inside the backoff window: poll until the batch goes
    // through (calls inside the window are side-effect-free) or the deadline hits.
    private int publishUntilSent(int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int sent;
        do {
            sent = publisher.publishBatch();
            if (sent == expected) return sent;
            Thread.sleep(250);
        } while (System.currentTimeMillis() < deadline);
        return sent;
    }
    @AfterEach void cleanup() {
        jdbc.sql("DELETE FROM instruction_audit WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM outbox WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM instruction WHERE run_id = ?").param(run).update();
    }
    private StoredInstruction admit(String key, String ref, long amount) {
        return service.admit(key, "corr-" + key,
                new PaymentInstruction(ref, run, "SENDER_A", "RECEIVER_B", amount, "USD")).instruction();
    }
    private List<String> browseRunPayloads() {
        return jms.browseSelected(queueIn, "runId = '" + run + "'", (session, browser) -> {
            var payloads = new ArrayList<String>();
            var messages = browser.getEnumeration();
            while (messages.hasMoreElements()) payloads.add(((TextMessage) messages.nextElement()).getText());
            return payloads;
        });
    }
    private long pendingForRun() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox o JOIN instruction i ON o.instruction_id = i.instruction_id WHERE o.published_at IS NULL AND i.run_id = ?")
                .param(run).query(Long.class).single();
    }
    private List<String> eventIdsForRun() {
        return jdbc.sql("SELECT o.event_id FROM outbox o JOIN instruction i ON o.instruction_id = i.instruction_id WHERE i.run_id = ? ORDER BY o.outbox_id")
                .param(run).query(String.class).list();
    }
    @Test void admissionStoresOneEventAndRepeatStoresNone() {
        var saved = admit(run + "-k1", "ref-1", 100L);
        var rows = eventIdsForRun();
        assertEquals(1, rows.size());
        String stored = jdbc.sql("SELECT payload FROM outbox WHERE event_id = ?").param(rows.get(0)).query(String.class).single();
        var envelope = InstructionReceivedEvent.fromJson(mapper, stored);
        assertEquals(1, envelope.schemaVersion());
        assertEquals("InstructionReceived", envelope.eventType());
        assertEquals(rows.get(0), envelope.eventId());
        assertEquals(saved.instructionId().toString(), envelope.instructionId());
        assertEquals("corr-" + run + "-k1", envelope.correlationId());
        assertEquals(run, envelope.runId());
        assertEquals(RequestHash.of(saved.payload()), envelope.payload().requestHash());
        var repeat = service.admit(run + "-k1", "other", saved.payload());
        assertTrue(repeat.repeated());
        assertEquals(1, eventIdsForRun().size());
    }
    @Test void rollbackStoresNeitherInstructionNorEvent() {
        var template = new TransactionTemplate(txManager);
        var instruction = new StoredInstruction(UUID.randomUUID(), run + "-rb", "corr",
                new PaymentInstruction("ref", run, "SENDER_A", "RECEIVER_B", 3L, "USD"),
                Instant.now(), Instant.now(), "RECEIVED", null, 0);
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> template.<Void>execute(s -> {
            instructions.save(instruction);
            outbox.insert("dup-event", instruction.instructionId(), "InstructionReceived", "{}", null, null);
            outbox.insert("dup-event", instruction.instructionId(), "InstructionReceived", "{}", null, null);
            return null;
        }));
        assertTrue(instructions.byKey(run + "-rb").isEmpty());
        assertEquals(0, pendingForRun());
    }
    @Test void publishesInOrderAndMarks() {
        admit(run + "-o0", "ref-0", 10L);
        admit(run + "-o1", "ref-1", 11L);
        admit(run + "-o2", "ref-2", 12L);
        assertEquals(3, publisher.publishBatch());
        assertEquals(0, pendingForRun());
        var browsed = browseRunPayloads();
        assertEquals(3, browsed.size());
        var browsedIds = browsed.stream().map(p -> InstructionReceivedEvent.fromJson(mapper, p).eventId()).toList();
        assertEquals(eventIdsForRun(), browsedIds);
    }
    @Test void crashAfterCommitPreservesEventId() throws Exception {
        admit(run + "-c0", "ref-0", 20L);
        marker.arm();
        assertEquals(0, publisher.publishBatch());
        assertEquals(1, pendingForRun());
        var firstDelivery = browseRunPayloads();
        assertEquals(1, firstDelivery.size());
        String eventId = InstructionReceivedEvent.fromJson(mapper, firstDelivery.get(0)).eventId();
        // Message is on the queue but the row stayed pending: the marker was never written.
        jdbc.sql("UPDATE outbox SET next_due_at = now() WHERE event_id = ?").param(eventId).update();
        assertEquals(1, publishUntilSent(1, 15_000));
        assertEquals(0, pendingForRun());
        var redelivered = browseRunPayloads();
        assertEquals(2, redelivered.size());
        assertTrue(redelivered.stream()
                .map(p -> InstructionReceivedEvent.fromJson(mapper, p).eventId())
                .allMatch(eventId::equals));
    }
    @Test void deferredHeadHoldsFollowersUntilDue() {
        admit(run + "-q0", "ref-0", 50L);
        admit(run + "-q1", "ref-1", 51L);
        admit(run + "-q2", "ref-2", 52L);
        marker.arm();
        assertEquals(0, publisher.publishBatch());
        assertEquals(3, pendingForRun());
        // Backoff reset isolates ordering: followers must wait for the
        // deferred head, not publish ahead of it.
        publisher.resetBackoffForTest();
        assertEquals(0, publisher.publishBatch());
        assertEquals(3, pendingForRun());
        jdbc.sql("UPDATE outbox SET next_due_at = now() WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)")
                .param(run).update();
        assertEquals(3, publisher.publishBatch());
        assertEquals(0, pendingForRun());
    }
    @Test void haltAfterMqCommitTripsBeforeMarker() throws Exception {
        admit(run + "-h0", "ref-0", 40L);
        faults.armThrowPublisherAfterCommitOnceForTest();
        // Throw mode trips like a crash: the send committed, the marker never
        // wrote, and no deferral bookkeeping ran (a real halt writes nothing).
        assertThrows(dev.queuedesk.fault.FaultLatches.Tripped.class, publisher::publishBatch);
        assertEquals(1, pendingForRun());
        var firstDelivery = browseRunPayloads();
        assertEquals(1, firstDelivery.size());
        String eventId = InstructionReceivedEvent.fromJson(mapper, firstDelivery.get(0)).eventId();
        assertEquals(1, publisher.publishBatch());
        assertEquals(0, pendingForRun());
        var redelivered = browseRunPayloads();
        assertEquals(2, redelivered.size());
        assertTrue(redelivered.stream()
                .map(p -> InstructionReceivedEvent.fromJson(mapper, p).eventId())
                .allMatch(eventId::equals));
    }
    @Test void outageSurvival() throws Exception {
        admit(run + "-d0", "ref-0", 30L);
        admit(run + "-d1", "ref-1", 31L);
        docker("stop", "qdesk-mq");
        try {
            assertEquals(0, publisher.publishBatch());
            assertEquals(2, pendingForRun());
            Long attempts = jdbc.sql("SELECT SUM(publish_attempts) FROM outbox o JOIN instruction i ON o.instruction_id = i.instruction_id WHERE i.run_id = ?")
                    .param(run).query(Long.class).single();
            assertTrue(attempts >= 1);
        } finally {
            docker("start", "qdesk-mq");
            waitForQmgr();
        }
        var before = eventIdsForRun();
        assertEquals(2, publishUntilSent(2, 30_000));
        assertEquals(0, pendingForRun());
        var after = browseRunPayloads().stream()
                .map(p -> InstructionReceivedEvent.fromJson(mapper, p).eventId()).toList();
        assertEquals(before, after);
    }
    // The worker test class runs later in the same suite with a live consumer: leave
    // no browsed-but-unconsumed messages behind, or they would process rows this
    // class already deleted and halt its consumer as unsupported.
    @org.junit.jupiter.api.AfterAll
    static void purgeQueue(@Autowired org.springframework.jms.core.JmsTemplate jms,
            @Value("${queuedesk.mq.queue-in}") String queueIn) {
        // JMS drain, not MQSC CLEAR: CLEAR refuses with "object in use" while this
        // JVM's cached connection is alive; a plain consumer always works.
        jms.setReceiveTimeout(1000);
        while (jms.receive(queueIn) != null) { /* drain */ }
    }
    private static void docker(String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add("docker");
        command.addAll(Arrays.asList(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "docker timed out");
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }
    private static void waitForQmgr() throws Exception {
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
            var probe = new ProcessBuilder("docker", "exec", "qdesk-mq", "dspmq", "-m", "QDESK")
                    .redirectErrorStream(true).start();
            String output = new String(probe.getInputStream().readAllBytes());
            probe.waitFor(15, TimeUnit.SECONDS);
            if (output.contains("STATUS(Running)")) return;
            Thread.sleep(5_000);
        }
        fail("queue manager did not return to Running");
    }
}

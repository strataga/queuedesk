package dev.queuedesk.worker;

import tools.jackson.databind.ObjectMapper;
import dev.queuedesk.domain.PaymentInstruction;
import dev.queuedesk.domain.StoredInstruction;
import dev.queuedesk.messaging.InstructionReceivedEvent;
import dev.queuedesk.messaging.OutboxPublisher;
import dev.queuedesk.service.AdmissionService;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.core.JmsTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Worker proofs against real Postgres and real MQ with the live listener:
 * success and business-rejection paths, redelivery dedup, crash-window
 * recovery, rollback pacing, poison routing to backout, the broken-route
 * backstop, quarantine projection, reconciliation, and metrics agreement.
 * Integration suite; the publisher stays manual for deterministic sends.
 */
@Tag("integration")
@SpringBootTest(properties = {"queuedesk.publisher.enabled=false", "queuedesk.worker.enabled=true",
        "queuedesk.reconcile.enabled=false", "queuedesk.quarantine.enabled=false"})
class WorkerTest {
    @Autowired AdmissionService service;
    @Autowired ProcessingService processing;
    @Autowired WorkerListener listener;
    @Autowired dev.queuedesk.fault.FaultLatches faults;
    @Autowired OutboxPublisher publisher;
    @Autowired JmsListenerEndpointRegistry registry;
    @Autowired JmsTemplate jms;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired ProcessedMessageRepository processed;
    @Autowired ProcessingAttemptRepository attempts;
    @Autowired QuarantineRepository quarantine;
    @Autowired QuarantineReconciler reconciler;
    @Autowired dev.queuedesk.http.QuarantineController quarantineApi;
    @Autowired ReceiverResultRepository receiverResults;
    @Autowired ReconciliationTaskRepository tasks;
    @Autowired ReconciliationPoller poller;
    @Autowired OutboxPublisher.MarkerWriter markerWriter;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    @Value("${queuedesk.mq.queue-in}") String queueIn;
    @Value("${queuedesk.mq.queue-backout}") String queueBackout;
    private String run;
    @BeforeEach void freshRun() { run = "w-" + UUID.randomUUID(); publisher.resetBackoffForTest(); faults.resetForTest(); }
    @BeforeAll static void requireConsumer(@Autowired JmsListenerEndpointRegistry registry) {
        assertTrue(registry.getListenerContainer(WorkerListener.LISTENER_ID).isRunning(),
                "worker consumer did not start; check queuedesk.worker.enabled and MQ");
    }
    // Suite order is not contractual: drain any stale messages before the first
    // test so only this class's runs are ever consumed here.
    @BeforeAll static void purgeStale(@Autowired JmsTemplate jms,
            @Value("${queuedesk.mq.queue-in}") String queueIn) {
        jms.setReceiveTimeout(1000);
        while (jms.receive(queueIn) != null) { /* drain */ }
    }
    @AfterEach void cleanup() {
        jdbc.sql("DELETE FROM processing_attempt WHERE instruction_id IS NULL").update();
        jdbc.sql("DELETE FROM quarantine_record WHERE instruction_id IS NULL").update();
        jdbc.sql("DELETE FROM quarantine_record WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM reconciliation_task WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM receiver_result WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM processing_attempt WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM processed_message WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM instruction_audit WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM outbox WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM instruction WHERE run_id = ?").param(run).update();
    }
    private StoredInstruction admit(String key, String ref, long amount) {
        return service.admit(key, "corr-" + key,
                new PaymentInstruction(ref, run, "SENDER_A", "RECEIVER_B", amount, "USD")).instruction();
    }
    private void publishAll() {
        int sent;
        do {
            sent = publisher.publishBatch();
        } while (sent > 0);
    }
    private AdmissionService.Read waitForState(UUID id, String state, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        AdmissionService.Read read;
        do {
            read = service.get(id);
            if (read.instruction().state().equals(state)) return read;
            Thread.sleep(250);
        } while (System.currentTimeMillis() < deadline);
        fail("instruction " + id + " never reached " + state + " (last: " + read.instruction().state() + ")");
        throw new AssertionError("unreachable");
    }
    private List<String> auditTrail(UUID id) {
        return jdbc.sql("SELECT COALESCE(old_state, 'null') || '->' || new_state FROM instruction_audit WHERE instruction_id = ? ORDER BY audit_id")
                .param(id).query(String.class).list();
    }
    // The poller claims globally, so on a shared lab a pass may also resolve
    // fellow travelers (smoke/scenario tasks). The per-instruction proof is
    // that THIS instruction's own task completed; global return counts are not
    // contractual and must never be asserted here.
    private void reconcileOwnTask(UUID instructionId) {
        assertTrue(tasks.findOpen(instructionId).isPresent(), "no open task for " + instructionId);
        poller.reconcilePass();
        assertTrue(tasks.findOpen(instructionId).isEmpty(), "own task still open for " + instructionId);
    }
    @Test void successPathCompletesWithVisibleIntermediateState() throws Exception {
        double outcomes0 = meters.counter("queuedesk.outcomes.total", "result", "COMPLETED_SIMULATED").count();
        var saved = admit(run + "-s0", "ref-0", 100L);
        publishAll();
        var done = waitForState(saved.instructionId(), "COMPLETED_SIMULATED", 60_000);
        assertNull(done.instruction().reason());
        assertEquals(outcomes0 + 1, meters.counter("queuedesk.outcomes.total", "result", "COMPLETED_SIMULATED").count());
        assertEquals("PUBLISHED", done.delivery());
        assertFalse(done.instruction().updatedAt().isBefore(done.instruction().receivedAt()));
        assertEquals(1, processed.countForInstruction(saved.instructionId()));
        assertEquals(List.of("null->RECEIVED", "RECEIVED->BUSINESS_ACCEPTED", "BUSINESS_ACCEPTED->COMPLETED_SIMULATED"),
                auditTrail(saved.instructionId()));
        assertEquals(1, attempts.countResult(saved.instructionId(), "SUCCESS"));
    }
    @Test void businessRejectionCommitsAndAcknowledgesNormally() throws Exception {
        var saved = admit(run + "-r0", "reject-too-big", 100L);
        publishAll();
        var done = waitForState(saved.instructionId(), "REJECTED", 60_000);
        assertEquals(FixtureClearingHousePolicy.FIXTURE_REJECT, done.instruction().reason());
        assertEquals(1, processed.countForInstruction(saved.instructionId()));
        assertEquals(1, attempts.countResult(saved.instructionId(), "BUSINESS_REJECT"));
        // Acknowledged, not looping: nothing of this run remains on the queue.
        Thread.sleep(2_000);
        assertEquals(0, browseRunPayloads().size());
        assertEquals(List.of("null->RECEIVED", "RECEIVED->REJECTED"), auditTrail(saved.instructionId()));
    }
    @Test void redeliveryYieldsOneOutcomeWithVisibleDedup() throws Exception {
        var saved = admit(run + "-d0", "ref-0", 100L);
        String eventId = jdbc.sql("SELECT event_id FROM outbox WHERE instruction_id = ?")
                .param(saved.instructionId()).query(String.class).single();
        String payload = jdbc.sql("SELECT payload FROM outbox WHERE event_id = ?")
                .param(eventId).query(String.class).single();
        // Same logical event delivered twice, as after a crash between DB and JMS commit.
        for (int i = 0; i < 2; i++) {
            jms.send(queueIn, session -> {
                var message = session.createTextMessage(payload);
                message.setStringProperty("eventId", eventId);
                message.setStringProperty("runId", run);
                return message;
            });
        }
        double dedup0 = meters.counter("queuedesk.dedup.total").count();
        var done = waitForState(saved.instructionId(), "COMPLETED_SIMULATED", 60_000);
        assertEquals("COMPLETED_SIMULATED", done.instruction().state());
        Thread.sleep(2_000); // Let the duplicate arrive and absorb.
        assertEquals(dedup0 + 1, meters.counter("queuedesk.dedup.total").count());
        assertEquals(1, processed.countForInstruction(saved.instructionId()));
        assertEquals(1, attempts.countResult(saved.instructionId(), "SUCCESS"));
        assertEquals(1, attempts.countResult(saved.instructionId(), "DUPLICATE_SKIPPED"));
        assertEquals(3, auditTrail(saved.instructionId()).size());
    }
    @Test void haltBeforeCommitRedeliversCleanly() throws Exception {
        faults.armThrowWorkerBeforeCommitOnceForTest();
        var saved = admit(run + "-hb0", "ref-0", 100L);
        publishAll();
        // Delivery 1 tripped before any DB write; redelivery ran the whole
        // business path once — no failure evidence, no duplicate.
        waitForState(saved.instructionId(), "COMPLETED_SIMULATED", 60_000);
        assertEquals(1, attempts.countResult(saved.instructionId(), "SUCCESS"));
        assertEquals(0, attempts.countResult(saved.instructionId(), "FAILED"));
        assertEquals(0, attempts.countResult(saved.instructionId(), "DUPLICATE_SKIPPED"));
        assertEquals(1, processed.countForInstruction(saved.instructionId()));
        assertEquals(3, auditTrail(saved.instructionId()).size());
    }
    @Test void haltAfterCommitAbsorbsRedeliveryOnce() throws Exception {
        faults.armThrowWorkerAfterCommitOnceForTest();
        var saved = admit(run + "-ha0", "ref-0", 100L);
        publishAll();
        waitForState(saved.instructionId(), "COMPLETED_SIMULATED", 60_000);
        // Delivery 1 committed the business result, then tripped before the
        // JMS commit; redelivery absorbed as a duplicate — one outcome,
        // visible dedup, receiver called once.
        long deadline = System.currentTimeMillis() + 30_000;
        while (attempts.countResult(saved.instructionId(), "DUPLICATE_SKIPPED") < 1
                && System.currentTimeMillis() < deadline) Thread.sleep(250);
        assertEquals(1, attempts.countResult(saved.instructionId(), "SUCCESS"));
        assertEquals(1, attempts.countResult(saved.instructionId(), "DUPLICATE_SKIPPED"));
        assertEquals(1, processed.countForInstruction(saved.instructionId()));
        assertEquals(1, receiverResults.find(saved.instructionId()).orElseThrow().calls());
        assertEquals(3, auditTrail(saved.instructionId()).size());
    }
    @Test void dbFailureRollsBackDeliveryAndRedelivers() throws Exception {
        processing.armDbFaultOnce();
        var saved = admit(run + "-f0", "ref-0", 100L);
        long start = System.currentTimeMillis();
        publishAll();
        var done = waitForState(saved.instructionId(), "COMPLETED_SIMULATED", 60_000);
        assertEquals("COMPLETED_SIMULATED", done.instruction().state());
        // The failed transaction left no business trace, but its evidence survived
        // via the controlled post-rollback write; redelivery then completed once.
        assertEquals(1, attempts.countResult(saved.instructionId(), "FAILED"));
        assertEquals(1, attempts.countResult(saved.instructionId(), "SUCCESS"));
        assertEquals(1, processed.countForInstruction(saved.instructionId()));
        assertEquals(3, auditTrail(saved.instructionId()).size());
        // RETRY-01: provider delivery counts beside the app counter, and pacing
        // proof — the first failure slept backoffMs(1) ~= 2s before redelivery.
        var counts = jdbc.sql("SELECT result || ':' || delivery_count FROM processing_attempt WHERE instruction_id = ? ORDER BY attempt_id")
                .param(saved.instructionId()).query(String.class).list();
        assertEquals(List.of("FAILED:1", "SUCCESS:2"), counts);
        assertTrue(System.currentTimeMillis() - start >= 1500, "redelivery was not paced");
    }
    @Test void poisonRoutesToBackoutAfterThreeDeliveries() throws Exception {
        var container = registry.getListenerContainer(WorkerListener.LISTENER_ID);
        assertTrue(container.isRunning());
        String poison = "{not valid json " + run;
        jms.send(queueIn, session -> {
            var message = session.createTextMessage(poison);
            message.setStringProperty("runId", run);
            return message;
        });
        try {
            // App counter and provider counter agree: exactly three failed
            // processing deliveries (counts 1,2,3), then the app routes.
            // Delta-based: anonymous poison carries no event/run key, so the
            // test measures growth from a captured baseline instead of
            // asserting global counts that a dirty lab would break.
            long unsupportedBefore = countAttempts("UNSUPPORTED");
            long routedBefore = countAttempts("ROUTED");
            long deadline = System.currentTimeMillis() + 60_000;
            while (countAttempts("UNSUPPORTED") < unsupportedBefore + 3
                    && System.currentTimeMillis() < deadline) Thread.sleep(250);
            var counts = jdbc.sql("SELECT delivery_count FROM processing_attempt WHERE result = 'UNSUPPORTED' AND event_id IS NULL ORDER BY attempt_id DESC LIMIT 3")
                    .query(Integer.class).list();
            assertEquals(List.of(3, 2, 1), counts);
            deadline = System.currentTimeMillis() + 60_000;
            while (countAttempts("ROUTED") < routedBefore + 1
                    && System.currentTimeMillis() < deadline) Thread.sleep(250);
            assertEquals(routedBefore + 1, countAttempts("ROUTED"));
            // The ROUTED row commits just before the session commit performs the
            // move; poll for the drain instead of assuming it.
            deadline = System.currentTimeMillis() + 30_000;
            while (!browseRunPayloads().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(250);
            assertEquals(0, browseRunPayloads().size(), "poison still on the input queue");
            var held = browseBackoutRun();
            assertTrue(held.contains(poison), "poison not on the backout queue: " + held);
            assertTrue(container.isRunning(), "consumer should keep running after routing poison");
        } finally {
            drainBackout();
        }
    }
    @Test void brokenRouteStopsConsumerAndPreservesWork() throws Exception {
        var container = registry.getListenerContainer(WorkerListener.LISTENER_ID);
        assertTrue(container.isRunning());
        // Break the route at the app seam: the route send resolves a queue that
        // does not exist (2085), exactly as a deleted backout would.
        listener.setQueueBackoutForTest("QUEUEDESK.NOPE");
        String poison = "{broken route " + run;
        try {
            jms.send(queueIn, session -> {
                var message = session.createTextMessage(poison);
                message.setStringProperty("runId", run);
                return message;
            });
            // Three failures, then the route attempt fails and halts.
            long deadline = System.currentTimeMillis() + 120_000;
            while (container.isRunning() && System.currentTimeMillis() < deadline) Thread.sleep(250);
            assertFalse(container.isRunning(), "consumer did not stop when the route failed");
            // Nothing discarded: the poison is still on the input queue.
            assertEquals(1, browseRunPayloads().size());
            assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM processing_attempt WHERE result = 'ROUTE_FAILED'")
                    .query(Long.class).single());
        } finally {
            listener.setQueueBackoutForTest(queueBackout);
            jms.setReceiveTimeout(2000);
            while (jms.receiveSelected(queueIn, "runId = '" + run + "'") != null) { /* purge */ }
            container.start();
            assertTrue(container.isRunning(), "consumer did not restart");
        }
    }
    @Test void reconcilerProjectsHeldWorkWithReasons() throws Exception {
        var saved = admit(run + "-q0", "ref-0", 100L);
        String payload = jdbc.sql("SELECT payload FROM outbox WHERE instruction_id = ?")
                .param(saved.instructionId()).query(String.class).single();
        String eventId = jdbc.sql("SELECT event_id FROM outbox WHERE instruction_id = ?")
                .param(saved.instructionId()).query(String.class).single();
        String badVersion = payload.replace("\"schemaVersion\":1", "\"schemaVersion\":99");
        assertTrue(badVersion.contains("\"schemaVersion\":99"));
        jms.send(queueIn, session -> {
            var message = session.createTextMessage(badVersion);
            message.setStringProperty("eventId", eventId);
            message.setStringProperty("runId", run);
            return message;
        });
        try {
            waitForAttempt(eventId, "ROUTED", 60_000);
            reconciler.reconcile();
            var items = quarantineApi.view().items().stream()
                    .filter(i -> eventId.equals(i.eventId())).toList();
            assertEquals(1, items.size());
            assertEquals(saved.instructionId(), items.get(0).instructionId());
            assertTrue(items.get(0).reason().contains("unsupported schemaVersion"), items.get(0).reason());
            var view = quarantineApi.view();
            assertFalse(view.stale(), "fresh reconcile must not read stale");
            assertNotNull(view.lastReconciledAt());
            assertEquals("QUARANTINED", service.get(saved.instructionId()).delivery());
            // Reconciling twice never duplicates the projection.
            reconciler.reconcile();
            assertEquals(1, quarantineApi.view().items().stream().filter(i -> eventId.equals(i.eventId())).count());
        } finally {
            drainBackout();
        }
    }
    @Test void drainedHoldsResolveOnNextReconcile() throws Exception {
        var saved = admit(run + "-r0", "ref-0", 100L);
        String payload = jdbc.sql("SELECT payload FROM outbox WHERE instruction_id = ?")
                .param(saved.instructionId()).query(String.class).single();
        String eventId = jdbc.sql("SELECT event_id FROM outbox WHERE instruction_id = ?")
                .param(saved.instructionId()).query(String.class).single();
        String badVersion = payload.replace("\"schemaVersion\":1", "\"schemaVersion\":99");
        jms.send(queueIn, session -> {
            var message = session.createTextMessage(badVersion);
            message.setStringProperty("eventId", eventId);
            message.setStringProperty("runId", run);
            return message;
        });
        try {
            waitForAttempt(eventId, "ROUTED", 60_000);
            reconciler.reconcile();
            assertEquals(1, quarantineApi.view().items().stream().filter(i -> eventId.equals(i.eventId())).count());
            assertEquals("QUARANTINED", service.get(saved.instructionId()).delivery());
            drainBackout();
            reconciler.reconcile();
            assertEquals(0, quarantineApi.view().items().stream().filter(i -> eventId.equals(i.eventId())).count());
            assertNotEquals("QUARANTINED", service.get(saved.instructionId()).delivery());
            assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM quarantine_record WHERE event_id = ? AND resolved_at IS NOT NULL")
                    .param(eventId).query(Long.class).single());
        } finally {
            drainBackout();
        }
    }
    @Test void secondClaimSkipsLeasedTasks() {
        var saved = admit(run + "-c0", "ref-0", 100L);
        tasks.create(saved.instructionId(), "evt-" + run, null, null);
        assertEquals(1, tasks.claimDue(10).size());
        // The first claim bumped next_due_at atomically: an immediate second
        // claim (a second poller) sees nothing due.
        assertEquals(0, tasks.claimDue(10).size());
        assertEquals(1, tasks.countOpenByRun(run));
    }
    @Test void markerCommitsOutsideOuterRollback() {
        var saved = admit(run + "-m0", "ref-0", 100L);
        long outboxId = jdbc.sql("SELECT outbox_id FROM outbox WHERE instruction_id = ?")
                .param(saved.instructionId()).query(Long.class).single();
        String eventId = jdbc.sql("SELECT event_id FROM outbox WHERE instruction_id = ?")
                .param(saved.instructionId()).query(String.class).single();
        new org.springframework.transaction.support.TransactionTemplate(txManager).execute(s -> {
            markerWriter.markPublished(new dev.queuedesk.messaging.OutboxRepository.DueRow(
                    outboxId, eventId, saved.instructionId(), "{}", 0, null, null));
            s.setRollbackOnly();
            return null;
        });
        assertTrue(outboxPublished(saved.instructionId()));
    }
    private boolean outboxPublished(UUID id) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox WHERE instruction_id = ? AND published_at IS NOT NULL")
                .param(id).query(Long.class).single() == 1;
    }
    private long countAttempts(String result) {
        return jdbc.sql("SELECT COUNT(*) FROM processing_attempt WHERE result = ? AND event_id IS NULL")
                .param(result).query(Long.class).single();
    }
    private long countNullQuarantine() {
        return jdbc.sql("SELECT COUNT(*) FROM quarantine_record WHERE event_id IS NULL")
                .query(Long.class).single();
    }
    @Test void reconcilerIdentifiesUnparseableByDigest() throws Exception {
        String poison = "{junk " + run + " " + UUID.randomUUID();
        jms.send(queueIn, session -> {
            var message = session.createTextMessage(poison);
            message.setStringProperty("runId", run);
            return message;
        });
        try {
            // Delta-based like the poison test: anonymous rows carry no key,
            // so growth from baseline plus the newest row carry the proof.
            long routedBefore = countAttempts("ROUTED");
            long nullBefore = countNullQuarantine();
            long deadline = System.currentTimeMillis() + 60_000;
            while (countAttempts("ROUTED") < routedBefore + 1
                    && System.currentTimeMillis() < deadline) Thread.sleep(250);
            reconciler.reconcile();
            assertEquals(nullBefore + 1, countNullQuarantine());
            var newest = jdbc.sql("SELECT event_id, instruction_id, reason FROM quarantine_record WHERE event_id IS NULL ORDER BY first_seen_at DESC LIMIT 1").query((rs, i) ->
                    (rs.getString("event_id") == null ? "null-event" : "has-event") + "/" + (rs.getObject("instruction_id") == null ? "null-instruction" : "has-instruction") + "/" + rs.getString("reason")).single();
            assertTrue(newest.startsWith("null-event/null-instruction/UNPARSEABLE_ENVELOPE"), newest);
            reconciler.reconcile();
            assertEquals(nullBefore + 1, countNullQuarantine());
        } finally {
            drainBackout();
        }
    }
    @Test void readsShowRetryingDuringRedelivery() throws Exception {
        var saved = admit(run + "-t0", "ref-0", 100L);
        // A FAILED attempt on a non-terminal instruction reads RETRYING; once the
        // worker completes it, the terminal state wins back PUBLISHED. The probe
        // row lands before publishing so no worker race can precede the first read.
        jdbc.sql("INSERT INTO processing_attempt (instruction_id, event_id, ended_at, result, reason, delivery_count) VALUES (?, 'probe', now(), 'FAILED', 'probe', 1)")
                .param(saved.instructionId()).update();
        assertEquals("RETRYING", service.get(saved.instructionId()).delivery());
        publishAll();
        waitForState(saved.instructionId(), "COMPLETED_SIMULATED", 60_000);
        assertEquals("PUBLISHED", service.get(saved.instructionId()).delivery());
    }
    @Test void lostReplyDefersPendingThenReconcilesFromEvidence() throws Exception {
        double reconciled0 = meters.counter("queuedesk.reconciled.total").count();
        var saved = admit(run + "-p0", "uncertain-0", 100L);
        publishAll();
        var pending = waitForState(saved.instructionId(), "PENDING_CONFIRMATION", 60_000);
        assertEquals("RECEIVER_RESPONSE_LOST", pending.instruction().reason());
        // TG01: the pending gauge agrees with durable state at the moment of truth.
        long pendingRows = jdbc.sql("SELECT COUNT(*) FROM instruction WHERE state = 'PENDING_CONFIRMATION'")
                .query(Long.class).single();
        assertEquals((double) pendingRows, meters.get("queuedesk.pending.confirmation.count").gauge().value());
        assertTrue(pendingRows >= 1);
        // D-03 atomicity: dedup key, pending state and exactly one open task.
        assertEquals(1, processed.countForInstruction(saved.instructionId()));
        var task = tasks.findOpen(saved.instructionId()).orElseThrow();
        assertEquals(0, task.attempts());
        assertEquals(1, attempts.countResult(saved.instructionId(), "PENDING"));
        // The receiver already holds the definitive result (lost reply only).
        assertEquals("COMPLETED", receiverResults.find(saved.instructionId()).orElseThrow().outcome());
        reconcileOwnTask(saved.instructionId());
        assertEquals(reconciled0 + 1, meters.counter("queuedesk.reconciled.total").count());
        var done = waitForState(saved.instructionId(), "COMPLETED_SIMULATED", 30_000);
        assertEquals("COMPLETED_SIMULATED", done.instruction().state());
        assertTrue(tasks.findOpen(saved.instructionId()).isEmpty());
        assertEquals(1, attempts.countResult(saved.instructionId(), "RECONCILED"));
        assertEquals(1, receiverResults.find(saved.instructionId()).orElseThrow().calls());
        assertEquals(List.of("null->RECEIVED", "RECEIVED->BUSINESS_ACCEPTED",
                "BUSINESS_ACCEPTED->PENDING_CONFIRMATION", "PENDING_CONFIRMATION->COMPLETED_SIMULATED"),
                auditTrail(saved.instructionId()));
    }
    @Test void redeliveryAfterPendingAbsorbsWithoutSecondEffect() throws Exception {
        var saved = admit(run + "-p1", "uncertain-1", 100L);
        String eventId = jdbc.sql("SELECT event_id FROM outbox WHERE instruction_id = ?")
                .param(saved.instructionId()).query(String.class).single();
        String payload = jdbc.sql("SELECT payload FROM outbox WHERE event_id = ?")
                .param(eventId).query(String.class).single();
        publishAll();
        waitForState(saved.instructionId(), "PENDING_CONFIRMATION", 60_000);
        // Crash-window redelivery of the same event: absorbed, no second task.
        jms.send(queueIn, session -> {
            var message = session.createTextMessage(payload);
            message.setStringProperty("eventId", eventId);
            message.setStringProperty("runId", run);
            return message;
        });
        long deadline = System.currentTimeMillis() + 30_000;
        while (attempts.countResult(saved.instructionId(), "DUPLICATE_SKIPPED") < 1
                && System.currentTimeMillis() < deadline) Thread.sleep(250);
        assertEquals(1, attempts.countResult(saved.instructionId(), "DUPLICATE_SKIPPED"));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM reconciliation_task WHERE instruction_id = ?")
                .param(saved.instructionId()).query(Long.class).single());
        assertEquals("PENDING_CONFIRMATION", service.get(saved.instructionId()).instruction().state());
        reconcileOwnTask(saved.instructionId());
        waitForState(saved.instructionId(), "COMPLETED_SIMULATED", 30_000);
    }
    @Test void receiverTruthSurvivesWorkerRollback() throws Exception {
        processing.armDbFaultAfterGatewayOnce();
        var saved = admit(run + "-g0", "ref-0", 100L);
        publishAll();
        waitForState(saved.instructionId(), "COMPLETED_SIMULATED", 60_000);
        // Delivery 1 committed the receiver row, then the worker rolled back;
        // delivery 2 repeated the call and completed on the same stored result.
        var row = receiverResults.find(saved.instructionId()).orElseThrow();
        assertEquals("COMPLETED", row.outcome());
        assertEquals(2, row.calls());
        assertEquals(1, processed.countForInstruction(saved.instructionId()));
    }
    @Test void uncertainRejectionReconcilesToRejected() throws Exception {
        var saved = admit(run + "-pr0", "uncertain-reject-0", 100L);
        publishAll();
        waitForState(saved.instructionId(), "PENDING_CONFIRMATION", 60_000);
        reconcileOwnTask(saved.instructionId());
        var done = waitForState(saved.instructionId(), "REJECTED", 30_000);
        assertEquals("FIXTURE_RECEIVER_REJECT", done.instruction().reason());
        assertTrue(tasks.findOpen(saved.instructionId()).isEmpty());
    }
    @Test void flakyResumesThroughReissue() throws Exception {
        var saved = admit(run + "-pf0", "flaky-0", 100L);
        publishAll();
        var pending = waitForState(saved.instructionId(), "PENDING_CONFIRMATION", 60_000);
        assertEquals("RECEIVER_CALL_UNCERTAIN", pending.instruction().reason());
        assertEquals("PENDING", receiverResults.find(saved.instructionId()).orElseThrow().outcome());
        reconcileOwnTask(saved.instructionId());
        waitForState(saved.instructionId(), "COMPLETED_SIMULATED", 30_000);
        assertEquals("COMPLETED", receiverResults.find(saved.instructionId()).orElseThrow().outcome());
        assertEquals(2, receiverResults.find(saved.instructionId()).orElseThrow().calls());
    }
    @Test void unknownStaysPendingWithoutATimer() throws Exception {
        var saved = admit(run + "-pu0", "unknown-0", 100L);
        publishAll();
        waitForState(saved.instructionId(), "PENDING_CONFIRMATION", 60_000);
        poller.reconcilePass(); // global return; own-task attempts below carry the proof
        assertEquals("PENDING_CONFIRMATION", service.get(saved.instructionId()).instruction().state());
        assertEquals(1, tasks.findOpen(saved.instructionId()).orElseThrow().attempts());
        // Force due twice more: attempts grow, nothing terminates, no replacement.
        for (int i = 0; i < 2; i++) {
            jdbc.sql("UPDATE reconciliation_task SET next_due_at = now() WHERE instruction_id = ?")
                    .param(saved.instructionId()).update();
            poller.reconcilePass(); // global return; own-task attempts below carry the proof
        }
        assertEquals(3, tasks.findOpen(saved.instructionId()).orElseThrow().attempts());
        assertEquals("PENDING_CONFIRMATION", service.get(saved.instructionId()).instruction().state());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM instruction WHERE run_id = ?").param(run)
                .query(Long.class).single());
    }
    private void waitForAttempt(String eventId, String result, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            long n = jdbc.sql("SELECT COUNT(*) FROM processing_attempt WHERE event_id = ? AND result = ?")
                    .params(eventId, result).query(Long.class).single();
            if (n >= 1) return;
            Thread.sleep(250);
        }
        fail("no " + result + " attempt for " + eventId);
    }
    private List<String> browseBackoutRun() {
        return jms.browseSelected(queueBackout, "runId = '" + run + "'", (session, browser) -> {
            var payloads = new ArrayList<String>();
            var messages = browser.getEnumeration();
            while (messages.hasMoreElements()) payloads.add(((TextMessage) messages.nextElement()).getText());
            return payloads;
        });
    }
    private void drainBackout() {
        jms.setReceiveTimeout(1000);
        while (jms.receive(queueBackout) != null) { /* drain */ }
    }
    @Test void readsShowOutcomeReasonDeliveryAndTimestamps() throws Exception {
        var saved = admit(run + "-v0", "ref-0", 100L);
        var fresh = service.get(saved.instructionId());
        assertEquals("RECEIVED", fresh.instruction().state());
        assertNull(fresh.instruction().reason());
        assertEquals("PENDING_PUBLISH", fresh.delivery());
        publishAll();
        var done = waitForState(saved.instructionId(), "COMPLETED_SIMULATED", 60_000);
        assertEquals("PUBLISHED", done.delivery());
        assertNotNull(done.instruction().receivedAt());
        assertNotNull(done.instruction().updatedAt());
        var page = service.list(run, "COMPLETED_SIMULATED", 50, null, null, null, false);
        assertEquals(1, page.items().size());
        assertEquals(saved.instructionId(), page.items().get(0).instruction().instructionId());
        assertEquals("PUBLISHED", page.items().get(0).delivery());
    }
    private List<String> browseRunPayloads() {
        return jms.browseSelected(queueIn, "runId = '" + run + "'", (session, browser) -> {
            var payloads = new ArrayList<String>();
            var messages = browser.getEnumeration();
            while (messages.hasMoreElements()) payloads.add(((TextMessage) messages.nextElement()).getText());
            return payloads;
        });
    }
}

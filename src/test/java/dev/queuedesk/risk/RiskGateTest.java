package dev.queuedesk.risk;

import dev.queuedesk.domain.PaymentInstruction;
import dev.queuedesk.domain.StoredInstruction;
import dev.queuedesk.fault.FaultLatches;
import dev.queuedesk.messaging.InstructionReceivedEvent;
import dev.queuedesk.service.AdmissionService;
import dev.queuedesk.worker.ProcessedMessageRepository;
import dev.queuedesk.worker.ProcessingService;
import dev.queuedesk.worker.ReceiverResultRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

// Gate proofs against real local Postgres (RISK-03, RISK-04, PR14-PR16):
// holds commit atomically, the receiver is never called on hold, faults hold
// safely, redeliveries reuse the cached assessment, and an audited resume
// releases through a continuation on the ORIGINAL instructionId. No MQ needed:
// envelopes are evaluated directly, exactly as the listener would.
// Isolation: this suite owns the queuedesk_risk database inside the named lab
// container (self-provisioned below). The shared `queuedesk` database is OUT:
// the live backend's publisher/worker would steal admission outbox rows and
// poison RISK_HELD instructions its older build cannot parse.
// OPS-02: integration suite — needs the named lab PG; CI runs it explicitly.
@Tag("integration")
@SpringBootTest(properties = {"queuedesk.publisher.enabled=false", "queuedesk.worker.enabled=false",
        "queuedesk.reconcile.enabled=false", "queuedesk.quarantine.enabled=false",
        "queuedesk.risk.mode=RULES_PLUS_MOCK", "queuedesk.risk.provider-deadline-ms=300",
        "spring.datasource.url=${QD_RISK_DB_URL:jdbc:postgresql://127.0.0.1:5442/queuedesk_risk}"})
class RiskGateTest {
    // Self-provisioning: CREATE DATABASE queuedesk_risk when missing (Flyway
    // migrates it on context boot). Maintenance connection derives from the
    // same env the datasource uses; loopback only, named container only.
    @BeforeAll
    static void provisionDatabase() throws Exception {
        String url = System.getenv().getOrDefault("QD_RISK_DB_URL",
                "jdbc:postgresql://127.0.0.1:5442/queuedesk_risk");
        if (!url.startsWith("jdbc:postgresql://127.0.0.1:") && !url.startsWith("jdbc:postgresql://localhost:"))
            throw new IllegalStateException("refusing non-loopback risk test database: " + url);
        String maintenance = url.replaceFirst("/[^/?]+(\\?.*)?$", "/postgres$1");
        String user = System.getenv().getOrDefault("QD_DB_USER", "queuedesk");
        String password = System.getenv().getOrDefault("QD_DB_PASSWORD", "queuedesk");
        try (var connection = java.sql.DriverManager.getConnection(maintenance, user, password)) {
            try (var exists = connection.createStatement();
                    var rows = exists.executeQuery("SELECT 1 FROM pg_database WHERE datname = 'queuedesk_risk'")) {
                if (rows.next()) return;
            }
            try (var create = connection.createStatement()) {
                create.execute("CREATE DATABASE queuedesk_risk");
            } catch (java.sql.SQLException raced) {
                if (!"42P04".equals(raced.getSQLState())) throw raced;
            }
        }
    }
    @Autowired AdmissionService service;
    @Autowired RiskGateService gate;
    @Autowired RiskReviewService reviews;
    @Autowired ProcessingService processing;
    @Autowired MockRiskSignalProvider mock;
    @Autowired RiskAssessmentRepository assessments;
    @Autowired ProcessedMessageRepository processed;
    @Autowired ReceiverResultRepository receiverResults;
    @Autowired FaultLatches faults;
    @Autowired JdbcClient jdbc;
    @Autowired dev.queuedesk.http.RiskController riskApi;
    @Autowired dev.queuedesk.http.QueuesController queuesApi;
    @Autowired dev.queuedesk.http.TelemetryController telemetryApi;
    @Autowired dev.queuedesk.http.ScenarioController scenarioApi;

    private String run;

    @BeforeEach void freshRun() {
        run = "rg-" + UUID.randomUUID();
        mock.clearFaultForTest();
        mock.resetCallsForTest();
        faults.resetForTest();
    }

    @AfterEach void cleanup() {
        mock.clearFaultForTest();
        faults.resetForTest();
        String inRun = " IN (SELECT instruction_id FROM instruction WHERE run_id = ?)";
        jdbc.sql("DELETE FROM risk_review WHERE instruction_id" + inRun).param(run).update();
        jdbc.sql("DELETE FROM risk_assessment WHERE instruction_id" + inRun).param(run).update();
        jdbc.sql("DELETE FROM risk_assessment_attempt WHERE instruction_id" + inRun).param(run).update();
        jdbc.sql("DELETE FROM receiver_result WHERE instruction_id" + inRun).param(run).update();
        jdbc.sql("DELETE FROM reconciliation_task WHERE instruction_id" + inRun).param(run).update();
        jdbc.sql("DELETE FROM processing_attempt WHERE instruction_id" + inRun).param(run).update();
        jdbc.sql("DELETE FROM processed_message WHERE instruction_id" + inRun).param(run).update();
        jdbc.sql("DELETE FROM instruction_audit WHERE instruction_id" + inRun).param(run).update();
        jdbc.sql("DELETE FROM outbox WHERE instruction_id" + inRun).param(run).update();
        jdbc.sql("DELETE FROM instruction WHERE run_id = ?").param(run).update();
    }

    private StoredInstruction admit(String ref, long amountMinor) {
        return service.admit(run + "-" + ref, "corr-" + ref,
                new PaymentInstruction(ref, run, "SENDER_A", "RECEIVER_B", amountMinor, "USD")).instruction();
    }

    private InstructionReceivedEvent envelope(StoredInstruction stored) {
        return InstructionReceivedEvent.of(UUID.randomUUID().toString(), stored, Instant.now());
    }

    private long assessmentCount(UUID instructionId) {
        return jdbc.sql("SELECT COUNT(*) FROM risk_assessment WHERE instruction_id = ?")
                .param(instructionId).query(Long.class).single();
    }

    @Test void newRecipientHoldsAndNeverCallsReceiver() {
        var stored = admit("first-in-run", 50_000);
        var first = envelope(stored);
        var verdict = gate.evaluate(first, 1);
        assertTrue(verdict.isPresent(), "first-in-run must hold (NEW_RECIPIENT)");
        assertEquals("RISK_HELD", verdict.get().outcome());
        assertEquals("RISK_HELD", verdict.get().state());
        assertEquals("RISK_HELD", service.get(stored.instructionId()).instruction().state());
        var assessment = assessments.latestByInstruction(stored.instructionId()).orElseThrow();
        assertEquals("REVIEW", assessment.policyResult());
        assertEquals(List.of("NEW_RECIPIENT"), assessment.reasonCodes());
        assertEquals("OK", assessment.evaluationStatus());
        assertNotNull(assessment.signalsJson(), "mock mode persists typed signals");
        assertNotNull(assessment.providerLatencyMs());
        // PR14: hold committed atomically with the marker — and the receiver
        // was never invoked (no row at all, let alone calls).
        assertTrue(processed.find(first.eventId()).isPresent());
        assertTrue(receiverResults.find(stored.instructionId()).isEmpty());
        assertEquals(1, mock.callsForTest(), "exactly one bounded provider call");
        // DASH-06: the instruction read carries the hold.
        var read = service.get(stored.instructionId());
        assertEquals("REVIEW", read.risk().policyResult());
    }

    @Test void knownQuietTrafficAllowsAndCompletes() {
        admit("baseline-quiet", 50_000);
        var stored = admit("quiet-second", 52_000);
        var verdict = gate.evaluate(envelope(stored), 1);
        assertTrue(verdict.isEmpty(), "known quiet traffic must ALLOW");
        var result = processing.process(envelope(stored), 1);
        assertEquals("SUCCESS", result.outcome());
        assertEquals("COMPLETED_SIMULATED", service.get(stored.instructionId()).instruction().state());
        assertEquals("ALLOW", assessments.latestByInstruction(stored.instructionId()).orElseThrow().policyResult());
    }

    @Test void nearCapNewRecipientRejectsUnderSimulationPolicy() {
        var stored = admit("whale-first", 9_600_000);
        var verdict = gate.evaluate(envelope(stored), 1);
        assertTrue(verdict.isPresent());
        assertEquals("BUSINESS_REJECT", verdict.get().outcome());
        assertEquals("REJECTED", service.get(stored.instructionId()).instruction().state());
        assertTrue(receiverResults.find(stored.instructionId()).isEmpty());
    }

    @Test void providerFaultsHoldUnassessedWithBoundedCalls() {
        var cases = List.of(
                new Case("TIMEOUT", "TIMEOUT"), new Case("INVALID", "INVALID_RESPONSE"),
                new Case("UNAVAILABLE", "PROVIDER_UNAVAILABLE"), new Case("UNKNOWN", "UNKNOWN"));
        int n = 0;
        for (var c : cases) {
            // Quiet traffic (receiver known, normal amount) so only the fault holds.
            if (n == 0) admit("fault-baseline", 50_000);
            // Each case needs a quiet instruction: same-run history keeps the
            // receiver known after the baseline.
            var stored = admit("fault-" + n, 51_000);
            mock.setFaultForTest(c.fault());
            var verdict = gate.evaluate(envelope(stored), 1);
            mock.clearFaultForTest();
            assertTrue(verdict.isPresent(), c.fault() + " must hold");
            assertEquals("RISK_HELD", verdict.get().state());
            assertTrue(verdict.get().reason().startsWith("NOT_ASSESSED"), verdict.get().reason());
            var assessment = assessments.latestByInstruction(stored.instructionId()).orElseThrow();
            assertEquals("NOT_ASSESSED", assessment.policyResult());
            assertEquals(c.status(), assessment.evaluationStatus(), c.fault());
            assertTrue(receiverResults.find(stored.instructionId()).isEmpty(), c.fault() + " must not call the receiver");
            n++;
        }
        assertEquals(4, mock.callsForTest(), "one bounded call per faulted instruction");
    }

    private record Case(String fault, String status) {}

    @Test void redeliveryReusesCachedAssessment() {
        var stored = admit("held-once", 50_000);
        var first = envelope(stored);
        assertTrue(gate.evaluate(first, 1).isPresent());
        assertEquals(1, mock.callsForTest());
        // Same eventId (MQ redelivery): the marker absorbs it in the normal
        // path — no second provider call, no second assessment.
        assertTrue(gate.evaluate(first, 2).isEmpty(), "handled event falls through to dedup");
        var duplicate = processing.process(first, 2);
        assertEquals("DUPLICATE_SKIPPED", duplicate.outcome());
        assertEquals(1, assessmentCount(stored.instructionId()));
        assertEquals(1, mock.callsForTest());
        // A NEW eventId for held work (no RESUME review) is an impossible
        // invariant: the gate stays out and the normal path poisons, exactly
        // as the base worker treats second claims on finished work.
        assertTrue(gate.evaluate(envelope(stored), 1).isEmpty());
        assertThrows(ProcessingService.UnsupportedEnvelope.class,
                () -> processing.process(envelope(stored), 1));
    }

    @Test void resumeReleasesThroughContinuationWithoutReplacement() {
        var stored = admit("held-then-resumed", 50_000);
        assertTrue(gate.evaluate(envelope(stored), 1).isPresent());
        var review = reviews.resume(stored.instructionId(), "lab-operator", "verified with sender");
        assertEquals("RESUME", review.decision());
        assertEquals("RECEIVED", service.get(stored.instructionId()).instruction().state());
        // The continuation carries the ORIGINAL instructionId under a new
        // deduplicated eventId; the gate sees the RESUME and does not re-hold.
        var continuation = InstructionReceivedEvent.of(review.continuationEventId(), stored, Instant.now());
        assertTrue(gate.evaluate(continuation, 1).isEmpty(), "audited RESUME must not re-hold");
        var result = processing.process(continuation, 1);
        assertEquals("SUCCESS", result.outcome());
        assertEquals(1, assessmentCount(stored.instructionId()), "no silent re-score on resume");
        assertEquals(1, mock.callsForTest());
        long rows = jdbc.sql("SELECT COUNT(*) FROM instruction WHERE run_id = ?").param(run).query(Long.class).single();
        assertEquals(1, rows, "no replacement instruction was created");
    }

    @Test void liveLeaseConflictsHoldVisiblyWithoutCalling() {
        var stored = admit("leased-elsewhere", 50_000);
        // Another evaluation owns the call right (or died holding it): this
        // delivery skips the provider and commits a visible NOT_ASSESSED
        // hold instead of burning retries toward the backout queue.
        assertTrue(assessments.acquireLease(stored.instructionId(), "1.0", "mock-2026-10", "0.4",
                "other-call", Instant.now().plusSeconds(60)));
        var verdict = gate.evaluate(envelope(stored), 1);
        assertTrue(verdict.isPresent());
        assertEquals("RISK_HELD", verdict.get().state());
        assertTrue(verdict.get().reason().startsWith("NOT_ASSESSED"), verdict.get().reason());
        var assessment = assessments.latestByInstruction(stored.instructionId()).orElseThrow();
        assertEquals("NOT_ASSESSED", assessment.policyResult());
        assertEquals("UNKNOWN", assessment.evaluationStatus());
        assertEquals(0, mock.callsForTest(), "conflicted delivery must not call the provider");
        assertTrue(receiverResults.find(stored.instructionId()).isEmpty());
    }

    @Test void latchRollsBackAssessmentAndRedeliveryRecovers() {
        var stored = admit("latched", 50_000);
        faults.armThrowRiskAfterAssessmentOnceForTest();
        assertThrows(FaultLatches.Tripped.class, () -> gate.evaluate(envelope(stored), 1));
        // Nothing committed: no assessment, no marker, still RECEIVED.
        assertEquals(0, assessmentCount(stored.instructionId()));
        assertEquals("RECEIVED", service.get(stored.instructionId()).instruction().state());
        // The lease released with the failure, so redelivery reassesses cleanly.
        var verdict = gate.evaluate(envelope(stored), 1);
        assertTrue(verdict.isPresent());
        assertEquals("RISK_HELD", verdict.get().state());
        assertEquals(1, assessmentCount(stored.instructionId()));
    }

    @Test void readPathsExposeHeldAssessment() {
        var stored = admit("held-for-reads", 50_000);
        gate.evaluate(envelope(stored), 1);
        // DASH-05 detail: rules, signals, versions, timeline.
        var detail = riskApi.detail(stored.instructionId());
        assertEquals("REVIEW", detail.riskOutput());
        assertEquals("RISK_HELD", detail.businessOutcome());
        assertTrue(detail.matchedRules().stream().anyMatch(r -> r.code().equals("NEW_RECIPIENT")));
        assertFalse(detail.signals().isEmpty());
        assertTrue(detail.signals().stream().allMatch(s -> s.confidenceNote().contains("never the probability of fraud")));
        assertEquals("1.0", detail.versions().featureSchema());
        assertNotNull(detail.totalMs());
        // DASH-05 list + summary.
        var list = riskApi.list("REVIEW", null, null, null, 20);
        assertTrue(list.getBody().items().stream()
                .anyMatch(i -> i.instructionId().equals(stored.instructionId())));
        assertThrows(IllegalArgumentException.class, () -> riskApi.list("BOGUS", null, null, null, 20));
        var summary = riskApi.summary(15);
        assertEquals("RULES_PLUS_MOCK", summary.mode());
        assertTrue(summary.riskHeld() >= 1);
        // DASH-01/02/04 smoke against live state.
        var queues = queuesApi.view(15);
        assertNotNull(queues.outbox());
        assertEquals("APPLICATION_ESTIMATE", queues.mq().depthEstimateNote());
        var telemetry = telemetryApi.summary(15);
        assertNotNull(telemetry.admission());
        assertEquals("RULES_PLUS_MOCK", telemetry.riskProvider().mode());
        assertEquals(19, scenarioApi.catalog().items().size(), "SC01-SC12 + RS01-RS06");
        var runDetail = scenarioApi.run(run);
        assertTrue(runDetail.uniqueAdmitted() >= 1);
    }
}

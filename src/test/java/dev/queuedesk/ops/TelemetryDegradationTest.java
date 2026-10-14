package dev.queuedesk.ops;

import dev.queuedesk.domain.PaymentInstruction;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

// TG03: with the OTLP endpoint dead, processing continues and the diagnostic
// loss is counted — business work never blocks on telemetry. The endpoint is
// pinned to a closed loopback port so the failure is deterministic.
// OPS-02: integration suite — needs the named lab PG + MQ; CI runs it explicitly.
@Tag("integration")
@SpringBootTest(properties = {"queuedesk.publisher.enabled=false", "queuedesk.worker.enabled=true",
        "queuedesk.reconcile.enabled=false", "queuedesk.quarantine.enabled=false",
        "queuedesk.telemetry.otlp-endpoint=http://127.0.0.1:9"})
class TelemetryDegradationTest {
    @Autowired dev.queuedesk.service.AdmissionService service;
    @Autowired dev.queuedesk.messaging.OutboxPublisher publisher;
    @Autowired SpanRingBuffer ring;
    @Autowired MeterRegistry registry;
    @Autowired JdbcClient jdbc;
    private String run;
    @BeforeEach void freshRun() {
        run = "tg03-" + UUID.randomUUID();
        publisher.resetBackoffForTest();
        ring.resetForTest();
    }
    @AfterEach void cleanup() {
        jdbc.sql("DELETE FROM receiver_result WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM processing_attempt WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM processed_message WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM instruction_audit WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM outbox WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM instruction WHERE run_id = ?").param(run).update();
    }
    @Test void processingContinuesWhileExportFailsVisibly() throws Exception {
        double failed0 = registry.counter("queuedesk.telemetry.export.spans.failed").count();
        double created0 = registry.counter("queuedesk.telemetry.spans.created").count();
        var saved = service.admit(run + "-t0", "corr-" + run,
                new PaymentInstruction("ref-0", run, "SENDER_A", "RECEIVER_B", 100L, "USD")).instruction();
        int sent;
        do {
            sent = publisher.publishBatch();
        } while (sent > 0);
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline
                && !service.get(saved.instructionId()).instruction().state().equals("COMPLETED_SIMULATED")) Thread.sleep(250);
        assertEquals("COMPLETED_SIMULATED", service.get(saved.instructionId()).instruction().state());
        assertTrue(registry.counter("queuedesk.telemetry.spans.created").count() > created0);
        assertFalse(ring.byName("queuedesk.worker.attempt").isEmpty(), "in-memory path unaffected");
        deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline
                && registry.counter("queuedesk.telemetry.export.spans.failed").count() <= failed0) Thread.sleep(250);
        assertTrue(registry.counter("queuedesk.telemetry.export.spans.failed").count() > failed0,
                "exporter failure must be counted");
        assertTrue(registry.counter("queuedesk.telemetry.export.spans.received").count() > 0);
    }
}

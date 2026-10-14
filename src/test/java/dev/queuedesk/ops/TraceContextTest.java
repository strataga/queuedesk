package dev.queuedesk.ops;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Trace context survives admission, outbox, MQ, worker attempt, and settle
 * as one trace with distinguishable attempt spans. Integration suite; the
 * publisher and poller stay manual while the worker runs live.
 */
@Tag("integration")
@SpringBootTest(properties = {"queuedesk.publisher.enabled=false", "queuedesk.worker.enabled=true",
        "queuedesk.reconcile.enabled=false", "queuedesk.quarantine.enabled=false"})
@AutoConfigureMockMvc
class TraceContextTest {
    @Autowired MockMvc mvc;
    @Autowired dev.queuedesk.messaging.OutboxPublisher publisher;
    @Autowired dev.queuedesk.worker.ReconciliationPoller poller;
    @Autowired dev.queuedesk.worker.ReconciliationTaskRepository tasks;
    @Autowired dev.queuedesk.service.AdmissionService service;
    @Autowired SpanRingBuffer ring;
    @Autowired JmsTemplate jms;
    @Autowired JdbcClient jdbc;
    @Value("${queuedesk.mq.queue-in}") String queueIn;
    private String run;
    @BeforeEach void freshRun() {
        run = "tg02-" + UUID.randomUUID();
        publisher.resetBackoffForTest();
        ring.resetForTest();
    }
    @AfterEach void cleanup() {
        jdbc.sql("DELETE FROM reconciliation_task WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM receiver_result WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM processing_attempt WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM processed_message WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM instruction_audit WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM outbox WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM instruction WHERE run_id = ?").param(run).update();
    }
    @Test void oneTraceFromAdmissionToSettle() throws Exception {
        String body = "{\"clientReference\":\"uncertain-tg02\",\"runId\":\"" + run + "\",\"senderParticipant\":\"SENDER_A\","
                + "\"receiverParticipant\":\"RECEIVER_B\",\"amountMinor\":100,\"currency\":\"USD\"}";
        MvcResult post = mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "tg02-" + run)
                .contentType("application/json").content(body)).andExpect(status().isAccepted()).andReturn();
        UUID instructionId = UUID.fromString(post.getResponse().getContentAsString()
                .replaceAll("(?s).*\"instructionId\":\"([^\"]+)\".*", "$1"));
        // The admission span's context committed with the outbox row.
        String stored = jdbc.sql("SELECT traceparent FROM outbox o JOIN instruction i ON o.instruction_id = i.instruction_id WHERE i.run_id = ?")
                .param(run).query(String.class).single();
        assertNotNull(stored);
        assertTrue(stored.startsWith("00-"), stored);
        int sent;
        do {
            sent = publisher.publishBatch();
        } while (sent > 0);
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline
                && !service.get(instructionId).instruction().state().equals("PENDING_CONFIRMATION")) Thread.sleep(250);
        assertEquals("PENDING_CONFIRMATION", service.get(instructionId).instruction().state());
        // Own-task proof, not a global claim count: a dirty lab may hold
        // other due tasks, so the return value is ignored and this task's
        // DONE row plus the COMPLETED state below carry the assertion.
        poller.reconcilePass();
        assertEquals("DONE", jdbc.sql("SELECT status FROM reconciliation_task WHERE instruction_id = ?")
                .param(instructionId).query(String.class).single());
        deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline
                && !service.get(instructionId).instruction().state().equals("COMPLETED_SIMULATED")) Thread.sleep(250);
        assertEquals("COMPLETED_SIMULATED", service.get(instructionId).instruction().state());
        var server = onlySpan("POST /api/v1/instructions");
        var publish = onlySpan("queuedesk.outbox.publish");
        var attempts = ring.byName("queuedesk.worker.attempt");
        assertEquals(1, attempts.size(), "one delivery, one attempt span");
        var attempt = attempts.get(0);
        var receiver = onlySpan("queuedesk.receiver.call");
        var settle = onlySpan("queuedesk.reconcile.settle");
        String trace = server.getTraceId();
        for (var span : List.of(publish, attempt, receiver, settle)) assertEquals(trace, span.getTraceId());
        assertFalse(server.getParentSpanContext().isValid(), "admission is the trace root");
        assertEquals(server.getSpanId(), publish.getParentSpanContext().getSpanId());
        assertEquals(publish.getSpanId(), attempt.getParentSpanContext().getSpanId());
        assertEquals(attempt.getSpanId(), receiver.getParentSpanContext().getSpanId());
        assertEquals(attempt.getSpanId(), settle.getParentSpanContext().getSpanId());
        String eventId = jdbc.sql("SELECT event_id FROM processed_message WHERE instruction_id = ?")
                .param(instructionId).query(String.class).single();
        assertEquals(eventId, attempt.getAttributes().get(AttributeKey.stringKey("messaging.event.id")));
        assertEquals("1", attempt.getAttributes().get(AttributeKey.stringKey("messaging.delivery.attempt")));
        assertEquals(instructionId.toString(), settle.getAttributes().get(AttributeKey.stringKey("business.instruction.id")));
        assertTrue(settle.getEvents().stream().anyMatch(e -> e.getName().equals("reconcile.resolved")));
    }
    private SpanData onlySpan(String name) {
        var found = ring.byName(name);
        assertEquals(1, found.size(), "expected exactly one " + name + " span");
        return found.get(0);
    }
}

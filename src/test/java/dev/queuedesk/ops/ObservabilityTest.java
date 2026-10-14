package dev.queuedesk.ops;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Bounded metrics agree with durable state: counters move by expected
 * deltas (the registry is shared per context) and gauges match live SQL.
 * Integration suite.
 */
@Tag("integration")
@SpringBootTest(properties = {"queuedesk.publisher.enabled=false", "queuedesk.worker.enabled=false",
        "queuedesk.reconcile.enabled=false", "queuedesk.quarantine.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class ObservabilityTest {
    @Autowired MockMvc mvc;
    @Autowired MeterRegistry registry;
    @Autowired JdbcClient jdbc;
    private String body(String run, String ref) {
        return "{\"clientReference\":\"" + ref + "\",\"runId\":\"" + run + "\",\"senderParticipant\":\"SENDER_A\","
                + "\"receiverParticipant\":\"RECEIVER_B\",\"amountMinor\":12500,\"currency\":\"USD\"}";
    }
    private double counter(String name, String tag, String value) {
        return registry.counter(name, tag, value).count();
    }
    @Test void admissionCountersAndGaugesAgreeWithState() throws Exception {
        String run = "obs-" + UUID.randomUUID();
        double accepted0 = counter("queuedesk.admissions.total", "decision", "accepted");
        double repeated0 = counter("queuedesk.admissions.total", "decision", "repeated");
        double conflict0 = counter("queuedesk.admissions.total", "decision", "conflict");
        double invalid0 = counter("queuedesk.admissions.total", "decision", "invalid");
        long timer0 = registry.find("queuedesk.admission.duration").tag("decision", "accepted").timer() == null ? 0
                : registry.find("queuedesk.admission.duration").tag("decision", "accepted").timer().count();
        mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "obs-a-" + run)
                .contentType("application/json").content(body(run, "ref-a"))).andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "obs-b-" + run)
                .contentType("application/json").content(body(run, "ref-b"))).andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "obs-a-" + run)
                .contentType("application/json").content(body(run, "ref-a"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "obs-a-" + run)
                .contentType("application/json").content(body(run, "ref-changed"))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "obs-bad-" + run)
                .contentType("application/json").content(body(run, "ref-bad").replace("12500", "0")))
                .andExpect(status().isBadRequest());
        assertEquals(accepted0 + 2, counter("queuedesk.admissions.total", "decision", "accepted"));
        assertEquals(repeated0 + 1, counter("queuedesk.admissions.total", "decision", "repeated"));
        assertEquals(conflict0 + 1, counter("queuedesk.admissions.total", "decision", "conflict"));
        assertEquals(invalid0 + 1, counter("queuedesk.admissions.total", "decision", "invalid"));
        assertEquals(timer0 + 2, registry.find("queuedesk.admission.duration").tag("decision", "accepted").timer().count());
        // Publisher is off: both admissions are still pending, and the gauge says so.
        long pending = jdbc.sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
        assertEquals((double) pending, registry.get("queuedesk.outbox.pending").gauge().value());
        assertTrue(pending >= 2);
        double age = registry.get("queuedesk.outbox.oldest.age.seconds").gauge().value();
        assertTrue(age >= 0, "oldest age must be non-negative");
        // The actuator surface exposes the meter without ID labels.
        mvc.perform(get("/actuator/metrics/queuedesk.admissions.total"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTags[?(@.tag=='decision')].values").exists())
                .andExpect(jsonPath("$.availableTags[?(@.tag=='instructionId')]").doesNotExist())
                .andExpect(jsonPath("$.availableTags[?(@.tag=='eventId')]").doesNotExist())
                .andExpect(jsonPath("$.availableTags[?(@.tag=='runId')]").doesNotExist());
    }
}

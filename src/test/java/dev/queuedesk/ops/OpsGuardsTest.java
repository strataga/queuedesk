package dev.queuedesk.ops;

import dev.queuedesk.domain.PaymentInstruction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// SAFE-01: the guards view agrees with durable state and halts traffic over
// the limit. Backlog limit is pinned to 0 so one admission deterministically
// trips the halt; disk assertions avoid host-dependent values.
// OPS-02: integration suite — needs the named lab PG + MQ; CI runs it explicitly.
@Tag("integration")
@SpringBootTest(properties = {"queuedesk.publisher.enabled=false", "queuedesk.worker.enabled=false",
        "queuedesk.reconcile.enabled=false", "queuedesk.quarantine.enabled=false",
        "queuedesk.guards.backlog-limit=0"})
@AutoConfigureMockMvc
@Transactional
class OpsGuardsTest {
    @Autowired MockMvc mvc;
    @Autowired dev.queuedesk.service.AdmissionService service;
    @Autowired JdbcClient jdbc;
    @Test void guardsAgreeAndHaltOverLimit() throws Exception {
        String run = "guard-" + UUID.randomUUID();
        service.admit(run + "-g0", "corr-" + run,
                new PaymentInstruction("ref-0", run, "SENDER_A", "RECEIVER_B", 100L, "USD"));
        long pending = jdbc.sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
        mvc.perform(get("/api/v1/ops/guards")).andExpect(status().isOk())
                .andExpect(jsonPath("$.outboxPending").value((int) pending))
                .andExpect(jsonPath("$.backlogLimit").value(0))
                .andExpect(jsonPath("$.backlogHalt").value(true))
                .andExpect(jsonPath("$.diskReserveBytes").value(10737418240L))
                .andExpect(jsonPath("$.haltTraffic").value(true))
                .andExpect(jsonPath("$.reasons[0]").exists());
        long free = jdbc.sql("SELECT 1").query(Long.class).single();
        assert free == 1;
        mvc.perform(get("/api/v1/ops/guards")).andExpect(jsonPath("$.diskFreeBytes").isNumber());
    }
}

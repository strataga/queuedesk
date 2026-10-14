package dev.queuedesk.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Read-only risk dashboard contracts: nullable risk fields on instruction
 * reads, the held list and summary, the usage ledger, and the deferred
 * review action staying 404. Integration suite.
 */
@Tag("integration")
@SpringBootTest(properties = {"queuedesk.publisher.enabled=false", "queuedesk.worker.enabled=false",
        "queuedesk.reconcile.enabled=false", "queuedesk.quarantine.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class RiskApiTest {
    @Autowired MockMvc mvc;
    @Autowired dev.queuedesk.risk.RiskAssessmentRepository risk;
    private final String body = """
        {"clientReference":"ref","runId":"test","senderParticipant":"SENDER_A",
        "receiverParticipant":"RECEIVER_B","amountMinor":12500,"currency":"USD"}
        """;

    @Test void instructionReadsCarryNullableRiskFields() throws Exception {
        var response = mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "risk-fields-" + UUID.randomUUID())
                .contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        // DASH-06: the three risk fields exist and are null with the gate OFF.
        mvc.perform(get(response.getHeader("Location")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskStatus").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.assessmentId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.riskReasonCodes").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test void riskHeldIsAListableStatus() throws Exception {
        mvc.perform(get("/api/v1/instructions").param("status", "RISK_HELD").param("limit", "5"))
                .andExpect(status().isOk());
    }

    @Test void riskSummaryAndHeldList() throws Exception {
        mvc.perform(get("/api/v1/risk/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("OFF"))
                .andExpect(jsonPath("$.riskHeld").exists());
        mvc.perform(get("/api/v1/risk/assessments").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.mode").value("OFF"));
        mvc.perform(get("/api/v1/risk/assessments/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test void riskSummaryCarriesTheUsageLedger() throws Exception {
        var before = risk.usageTotals();
        var admitted = mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "risk-usage-" + UUID.randomUUID())
                .contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        var location = admitted.getHeader("Location");
        assert location != null;
        var instructionId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        risk.save(new dev.queuedesk.risk.RiskAssessmentRepository.StoredAssessment(UUID.randomUUID(),
                instructionId, "1.0", "jev-q1", "0.4", "hash", "{}", "OK", "ALLOW", java.util.List.of(),
                "{}", 120L, java.time.Instant.now(), 392L, 65L));
        var after = risk.usageTotals();
        org.junit.jupiter.api.Assertions.assertEquals(before.calls() + 1, after.calls());
        org.junit.jupiter.api.Assertions.assertEquals(before.inputTokens() + 392, after.inputTokens());
        org.junit.jupiter.api.Assertions.assertEquals(before.outputTokens() + 65, after.outputTokens());
        mvc.perform(get("/api/v1/risk/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.calls").value(after.calls()))
                .andExpect(jsonPath("$.usage.inputTokens").value(after.inputTokens()))
                .andExpect(jsonPath("$.usage.budgetUsd").value(1.0))
                .andExpect(jsonPath("$.usage.exhausted").value(false));
    }

    @Test void reviewActionsStayDeferred() throws Exception {
        mvc.perform(post("/api/v1/risk/reviews").contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                    {"instructionId":"00000000-0000-0000-0000-000000000000",
                     "decision":"RESUME","reason":"lab","actor":"lab"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test void queuesTelemetryAndScenarios() throws Exception {
        mvc.perform(get("/api/v1/ops/queues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outbox").exists())
                .andExpect(jsonPath("$.mq.depthEstimateNote").value("APPLICATION_ESTIMATE"))
                .andExpect(jsonPath("$.quarantine.stale").exists());
        mvc.perform(get("/api/v1/telemetry/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admission").exists())
                .andExpect(jsonPath("$.exporter.status").exists());
        mvc.perform(get("/api/v1/telemetry/latency-series").param("metric", "admission"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").isArray());
        mvc.perform(get("/api/v1/telemetry/traces").param("limit", "5"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/telemetry/traces/00000000000000000000000000000000"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/v1/scenarios/runs").param("limit", "5"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/scenarios/runs/never-ran-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uniqueAdmitted").value(0));
    }
}

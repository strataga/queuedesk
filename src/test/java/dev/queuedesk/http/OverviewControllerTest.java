package dev.queuedesk.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Lab-wide overview reads: windowed instruction census, current backlog
 * gauges, throughput buckets, and the recent-payments table. Integration
 * suite. Reads see committed lab rows, so tests assert deltas over a
 * captured baseline.
 */
@Tag("integration")
@SpringBootTest(properties = {"queuedesk.publisher.enabled=false", "queuedesk.worker.enabled=false",
        "queuedesk.reconcile.enabled=false", "queuedesk.quarantine.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class OverviewControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    private final String body = """
        {"clientReference":"REF","runId":"ovw-RUN","senderParticipant":"SENDER_A",
        "receiverParticipant":"RECEIVER_B","amountMinor":100,"currency":"USD"}
        """;
    private JsonNode summary(String query) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/overview" + query))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }
    private void admit(String key, String ref, String run) throws Exception {
        mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body.replace("REF", ref).replace("ovw-RUN", run)))
                .andExpect(status().isAccepted());
    }
    @Test void summaryCountsAdmittedRows() throws Exception {
        JsonNode before = summary("");
        admit("ovw-k1", "ovw-ref-1", "ovw-run-1");
        admit("ovw-k2", "ovw-ref-2", "ovw-run-1");
        JsonNode after = summary("");
        assertEquals(2, after.get("admitted").asLong() - before.get("admitted").asLong());
        assertEquals(2, after.get("byState").path("RECEIVED").asLong(0)
                - before.get("byState").path("RECEIVED").asLong(0));
        assertEquals(2, after.get("outboxPending").asLong() - before.get("outboxPending").asLong());
        assertEquals(before.get("reconcileOpen").asLong(), after.get("reconcileOpen").asLong());
    }
    @Test void windowBoundsAdmissionTime() throws Exception {
        String pastQ = "?since=" + Instant.now().minusSeconds(3600);
        long pastBefore = summary(pastQ).get("admitted").asLong();
        admit("ovw-k3", "ovw-ref-3", "ovw-run-2");
        JsonNode future = summary("?since=" + Instant.now().plusSeconds(3600));
        JsonNode past = summary(pastQ);
        assertEquals(0, future.get("admitted").asLong());
        assertEquals(pastBefore + 1, past.get("admitted").asLong());
    }
    @Test void invertedWindowIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/overview?since=2026-10-06T00:00:00Z&until=2026-10-05T00:00:00Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/overview?since=not-a-time")).andExpect(status().isBadRequest());
    }
    private JsonNode throughput(String query) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/overview/throughput" + query))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }
    private static long total(JsonNode view) {
        long sum = 0;
        for (var p : view.get("points")) sum += p.get("count").asLong();
        return sum;
    }
    @Test void throughputBucketsCompletions(@Autowired dev.queuedesk.repository.InstructionRepository instructions)
            throws Exception {
        String q = "?since=" + Instant.now().minusSeconds(3600) + "&bucketSeconds=60";
        long before = total(throughput(q));
        admit("ovw-t1", "ovw-tref-1", "ovw-trun");
        admit("ovw-t2", "ovw-tref-2", "ovw-trun");
        for (String key : new String[] {"ovw-t1", "ovw-t2"}) {
            var id = instructions.byKey(key).orElseThrow().instructionId();
            assertTrue(instructions.transition(id, "RECEIVED", "BUSINESS_ACCEPTED", "test", "test"));
            assertTrue(instructions.transition(id, "BUSINESS_ACCEPTED", "COMPLETED_SIMULATED", "test", "test"));
        }
        JsonNode after = throughput(q);
        assertEquals("simulated_completions", after.get("metric").asText());
        assertEquals(60, after.get("bucketSeconds").asLong());
        assertEquals(before + 2, total(after));
        var points = after.get("points");
        assertTrue(points.size() >= 2);
        for (int i = 1; i < points.size(); i++) {
            long gap = Instant.parse(points.get(i).get("at").asText()).getEpochSecond()
                    - Instant.parse(points.get(i - 1).get("at").asText()).getEpochSecond();
            assertEquals(60, gap);
        }
    }
    @Test void recentListsNewestFirst() throws Exception {
        admit("ovw-r1", "ovw-rref-1", "ovw-rrun");
        admit("ovw-r2", "ovw-rref-2", "ovw-rrun");
        admit("ovw-r3", "ovw-rref-3", "ovw-rrun");
        MvcResult result = mvc.perform(get("/api/v1/overview/recent?limit=2"))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = mapper.readTree(result.getResponse().getContentAsString()).get("items");
        assertEquals(2, items.size());
        assertEquals("ovw-rref-3", items.get(0).get("payload").get("clientReference").asText());
        assertEquals("ovw-rref-2", items.get(1).get("payload").get("clientReference").asText());
        assertEquals("RECEIVED", items.get(0).get("status").asText());
        mvc.perform(get("/api/v1/overview/recent?limit=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/overview/recent?limit=21")).andExpect(status().isBadRequest());
    }
    @Test void throughputRejectsBadWindows() throws Exception {
        mvc.perform(get("/api/v1/overview/throughput?since=2026-10-06T00:00:00Z&until=2026-10-05T00:00:00Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/overview/throughput?bucketSeconds=5")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/overview/throughput?bucketSeconds=9999")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/overview/throughput?since=2020-01-01T00:00:00Z&bucketSeconds=10"))
                .andExpect(status().isBadRequest());
    }
}

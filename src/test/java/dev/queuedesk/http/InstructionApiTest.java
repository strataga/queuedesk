package dev.queuedesk.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP contracts against the real stack: admission, exact repeat, conflict,
 * reads, listing with cursors and time windows, trace links, and the
 * quarantine view. Integration suite; each method rolls back so keys stay
 * unique. Background schedulers stay off so cached contexts cannot steal
 * fellow suites' work.
 */
@Tag("integration")
@SpringBootTest(properties = {"queuedesk.publisher.enabled=false", "queuedesk.worker.enabled=false",
        "queuedesk.reconcile.enabled=false", "queuedesk.quarantine.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class InstructionApiTest {
    @Autowired MockMvc mvc;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final String body = """
        {"clientReference":"ref","runId":"test","senderParticipant":"SENDER_A",
        "receiverParticipant":"RECEIVER_B","amountMinor":12500,"currency":"USD"}
        """;
    @Test void admissionRepeatConflictAndRead() throws Exception {
        var response = mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "api-one")
                .contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.delivery").value("PENDING_PUBLISH")).andReturn().getResponse();
        mvc.perform(get(response.getHeader("Location"))).andExpect(status().isOk()).andExpect(jsonPath("$.payload.amountMinor").value(12500));
        mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "api-one").header("X-Correlation-Id", "new-request")
                .contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "api-one")
                .contentType("application/json").content(body.replace("12500", "12501")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }
    @Test void invalidRequestsAndUnknownIds() throws Exception {
        mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "bad")
                .contentType("application/json").content(body.replace("12500", "0"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/instructions").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "extra").contentType("application/json")
                .content(body.replace("12500", "12500.5"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/instructions/00000000-0000-0000-0000-000000000000")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/instructions?limit=101")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "null-body")
                .contentType("application/json").content("null")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/instructions").param("runId", "has spaces!")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/runs/has!bang")).andExpect(status().isBadRequest());
    }
    @Test void runSummaryReportsStatesAndOutbox() throws Exception {
        String runId = "summary-" + UUID.randomUUID();
        String template = body.replace("\"test\"", "\"" + runId + "\"");
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "sum-" + runId + "-" + i)
                    .contentType("application/json").content(template.replace("\"ref\"", "\"ref-" + i + "\"")))
                    .andExpect(status().isAccepted());
        }
        mvc.perform(get("/api/v1/runs/" + runId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.admitted").value(2))
                .andExpect(jsonPath("$.byState.RECEIVED").value(2))
                .andExpect(jsonPath("$.outboxPending").value(2))
                .andExpect(jsonPath("$.outboxPublished").value(0));
        mvc.perform(get("/api/v1/runs/no-such-run")).andExpect(status().isOk())
                .andExpect(jsonPath("$.admitted").value(0))
                .andExpect(jsonPath("$.outboxPending").value(0));
    }
    @Test void detailCarriesTraceIdFromOutboxParent() throws Exception {
        String key = "trace-" + UUID.randomUUID();
        var response = mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", key)
                .contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        String location = response.getHeader("Location");
        String id = location.substring(location.lastIndexOf('/') + 1);
        // Admission stamps a real traceparent: the view links the live trace.
        mvc.perform(get("/api/v1/instructions/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId",
                        org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")));
        jdbc.sql("UPDATE outbox SET traceparent = ? WHERE instruction_id = ?::uuid")
                .params("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", id).update();
        mvc.perform(get("/api/v1/instructions/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("4bf92f3577b34da6a3ce929d0e0e4736"));
    }
    @Test void quarantineViewShowsHeldItemsAndFreshness() throws Exception {
        // Pin the heartbeat inside this rolled-back transaction: other suites may
        // have committed reconciliations, so never assume the global state.
        jdbc.sql("UPDATE quarantine_projection_meta SET last_reconciled_at = NULL, last_error = NULL").update();
        jdbc.sql("INSERT INTO quarantine_record (message_digest, event_id, reason) VALUES ('digest-1', 'event-1', 'UNPARSEABLE_ENVELOPE')").update();
        // Items show with stale=true; a fresh heartbeat would flip it to false.
        mvc.perform(get("/api/v1/quarantine")).andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eventId").value("event-1"))
                .andExpect(jsonPath("$.items[0].reason").value("UNPARSEABLE_ENVELOPE"));
    }
    @Test void oversizedBodyIsRejected() throws Exception {
        String huge = "{\"padding\":\"" + "x".repeat(17000) + "\"}";
        mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "huge")
                .contentType("application/json").content(huge))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
    }
    @Test void listSupportsStatusCursorAndRejectsBadCursor() throws Exception {
        String runId = "page-" + UUID.randomUUID();
        String template = body.replace("\"test\"", "\"" + runId + "\"");
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "page-" + runId + "-" + i)
                    .contentType("application/json").content(template.replace("\"ref\"", "\"ref-" + i + "\"")))
                    .andExpect(status().isAccepted());
        }
        var first = mvc.perform(get("/api/v1/instructions").param("runId", runId).param("limit", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)).andReturn().getResponse();
        String cursor = first.getHeader("X-Next-Cursor");
        assert cursor != null && !cursor.isBlank();
        mvc.perform(get("/api/v1/instructions").param("runId", runId).param("limit", "2").param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(header().doesNotExist("X-Next-Cursor"));
        mvc.perform(get("/api/v1/instructions").param("runId", runId).param("status", "RECEIVED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/v1/instructions").param("status", "BOGUS")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/instructions").param("cursor", "!!!")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }
    @Test void listOrdersNewestFirstWithDirectionScopedCursors() throws Exception {
        String runId = "desc-" + UUID.randomUUID();
        String template = body.replace("\"test\"", "\"" + runId + "\"");
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "desc-" + runId + "-" + i)
                    .contentType("application/json").content(template.replace("\"ref\"", "\"dref-" + i + "\"")))
                    .andExpect(status().isAccepted());
        }
        var asc = refs(runId, "10", null, "asc");
        var desc = refs(runId, "10", null, "desc");
        java.util.Collections.reverse(asc);
        assertEquals(asc, desc);
        var first = mvc.perform(get("/api/v1/instructions").param("runId", runId)
                        .param("limit", "2").param("order", "desc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)).andReturn().getResponse();
        assertEquals(desc.subList(0, 2), refs(runId, "2", null, "desc"));
        String cursor = first.getHeader("X-Next-Cursor");
        assert cursor != null && !cursor.isBlank();
        mvc.perform(get("/api/v1/instructions").param("runId", runId)
                        .param("limit", "2").param("order", "desc").param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(header().doesNotExist("X-Next-Cursor"));
        assertEquals(desc.subList(2, 3), refs(runId, "2", cursor, "desc"));
        mvc.perform(get("/api/v1/instructions").param("order", "sideways")).andExpect(status().isBadRequest());
    }
    private java.util.List<String> refs(String runId, String limit, String cursor, String order) throws Exception {
        var req = get("/api/v1/instructions").param("runId", runId).param("limit", limit).param("order", order);
        if (cursor != null) req.param("cursor", cursor);
        String json = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var out = new java.util.ArrayList<String>();
        for (var node : mapper.readTree(json)) out.add(node.get("payload").get("clientReference").asText());
        return out;
    }
    @Test void listSupportsSinceUntilWindow() throws Exception {
        String runId = "window-" + UUID.randomUUID();
        String template = body.replace("\"test\"", "\"" + runId + "\"");
        String before = java.time.Instant.now().minusSeconds(60).toString();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "window-" + runId + "-" + i)
                    .contentType("application/json").content(template.replace("\"ref\"", "\"ref-" + i + "\"")))
                    .andExpect(status().isAccepted());
        }
        String after = java.time.Instant.now().plusSeconds(60).toString();
        String future = java.time.Instant.now().plusSeconds(3600).toString();
        mvc.perform(get("/api/v1/instructions").param("runId", runId).param("since", before).param("until", after))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/instructions").param("runId", runId).param("since", future))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/instructions").param("runId", runId).param("until", before))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/instructions").param("since", after).param("until", before))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/instructions").param("since", "not-a-time")).andExpect(status().isBadRequest());
    }
}

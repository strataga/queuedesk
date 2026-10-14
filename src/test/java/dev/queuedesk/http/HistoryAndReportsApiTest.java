package dev.queuedesk.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Read-only history and reports APIs: per-instruction audit timeline and
 * processing attempts, plus CLI report listing and serving straight off
 * disk behind a path-traversal guard. Integration suite. Reports point at
 * an isolated temp directory per class, never the repo's reports folder,
 * so global files cannot leak into assertions.
 */
@Tag("integration")
@SpringBootTest(properties = {"queuedesk.publisher.enabled=false", "queuedesk.worker.enabled=false",
        "queuedesk.reconcile.enabled=false", "queuedesk.quarantine.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class HistoryAndReportsApiTest {
    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private static Path reportsDir;

    @DynamicPropertySource
    static void isolatedReports(DynamicPropertyRegistry registry) throws Exception {
        reportsDir = Files.createTempDirectory("qd-reports-test-");
        registry.add("queuedesk.reports.dir", () -> reportsDir.toString());
    }

    private final String body = """
        {"clientReference":"ref","runId":"test","senderParticipant":"SENDER_A",
        "receiverParticipant":"RECEIVER_B","amountMinor":12500,"currency":"USD"}
        """;

    private String admit() throws Exception {
        return mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "hist-" + UUID.randomUUID())
                .contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getHeader("Location");
    }

    @Test void auditTimelineShowsAdmissionRow() throws Exception {
        String location = admit();
        mvc.perform(get(location + "/audit")).andExpect(status().isOk())
                .andExpect(jsonPath("$.instructionId").exists())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].oldState").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].newState").value("RECEIVED"))
                .andExpect(jsonPath("$.items[0].actor").value("admission"))
                .andExpect(jsonPath("$.items[0].recordedAt").exists());
        mvc.perform(get("/api/v1/instructions/00000000-0000-0000-0000-000000000000/audit"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test void attemptsStartEmptyThenShowRows() throws Exception {
        String location = admit();
        UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        mvc.perform(get(location + "/attempts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.instructionId").value(id.toString()))
                .andExpect(jsonPath("$.items.length()").value(0));
        jdbc.sql("INSERT INTO processing_attempt (instruction_id, event_id, result, reason, delivery_count)"
                + " VALUES (?, 'evt-1', 'DUPLICATE_SKIPPED', 'repeat delivery', 2)")
                .params(id).update();
        mvc.perform(get(location + "/attempts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eventId").value("evt-1"))
                .andExpect(jsonPath("$.items[0].result").value("DUPLICATE_SKIPPED"))
                .andExpect(jsonPath("$.items[0].reason").value("repeat delivery"))
                .andExpect(jsonPath("$.items[0].deliveryCount").value(2))
                .andExpect(jsonPath("$.items[0].startedAt").exists());
        mvc.perform(get("/api/v1/instructions/00000000-0000-0000-0000-000000000000/attempts"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test void reportsListPaginatesIsolatedDir() throws Exception {
        String tag = UUID.randomUUID().toString();
        Files.writeString(reportsDir.resolve("sc01-" + tag + ".json"), "{\"runId\":\"a\"}", StandardCharsets.UTF_8);
        Files.writeString(reportsDir.resolve("sc02-" + tag + ".json"), "{\"runId\":\"b\"}", StandardCharsets.UTF_8);
        Files.writeString(reportsDir.resolve("notes-" + tag + ".txt"), "not a report", StandardCharsets.UTF_8);
        var first = mvc.perform(get("/api/v1/scenarios/reports").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").exists())
                .andExpect(jsonPath("$.items[0].sizeBytes").exists())
                .andExpect(jsonPath("$.items[0].modifiedAt").exists())
                .andReturn().getResponse();
        String cursor = first.getHeader("X-Next-Cursor");
        assert cursor != null && !cursor.isBlank();
        mvc.perform(get("/api/v1/scenarios/reports").param("limit", "1").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        // No cross-talk: only this test's two .json files are visible, never .txt.
        mvc.perform(get("/api/v1/scenarios/reports").param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/api/v1/scenarios/reports").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/scenarios/reports").param("cursor", "!!!")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test void reportServeReturnsBytesAndGuardsTraversal() throws Exception {
        String name = "sc09-" + UUID.randomUUID() + ".json";
        String payload = "{\"tool\":\"queuedesk-generate\",\"runId\":\"r1\"}";
        Files.writeString(reportsDir.resolve(name), payload, StandardCharsets.UTF_8);
        mvc.perform(get("/api/v1/scenarios/reports/" + name)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/json")))
                .andExpect(content().json(payload));
        mvc.perform(get("/api/v1/scenarios/reports/missing-" + UUID.randomUUID() + ".json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/scenarios/reports/x.txt")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/scenarios/reports/.hidden.json")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        // Dots inside a well-formed name stay inside the dir: honest 404, never an escape.
        mvc.perform(get("/api/v1/scenarios/reports/a..b-" + UUID.randomUUID() + ".json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}

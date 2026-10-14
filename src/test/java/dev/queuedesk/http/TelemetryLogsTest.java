package dev.queuedesk.http;

import dev.queuedesk.ops.LogRingBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The live log-ring endpoint: rows are visible, level thresholds filter,
 * and bad levels or limits are rejected. Integration suite; unique markers
 * keep assertions robust to fellow suites' log rows.
 */
@Tag("integration")
@SpringBootTest(properties = {"queuedesk.publisher.enabled=false", "queuedesk.worker.enabled=false",
        "queuedesk.reconcile.enabled=false", "queuedesk.quarantine.enabled=false"})
@AutoConfigureMockMvc
class TelemetryLogsTest {
    private static final Logger log = LoggerFactory.getLogger(TelemetryLogsTest.class);
    @Autowired MockMvc mvc;
    @Autowired LogRingBuffer ring;
    @BeforeEach void clearRing() { ring.resetForTest(); }
    @Test void infoRowVisibleAndWarnThresholdFiltersIt() throws Exception {
        String marker = "logring-" + UUID.randomUUID();
        log.info(marker);
        mvc.perform(get("/api/v1/telemetry/logs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.message=='" + marker + "')]").exists());
        mvc.perform(get("/api/v1/telemetry/logs").param("level", "WARN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.message=='" + marker + "')]").doesNotExist());
    }
    @Test void badLevelAndLimitAreRejected() throws Exception {
        mvc.perform(get("/api/v1/telemetry/logs").param("level", "BOGUS")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/telemetry/logs").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/telemetry/logs").param("limit", "501")).andExpect(status().isBadRequest());
    }
}

package dev.queuedesk.ops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// OPS-01: liveness is the process, readiness is the dependencies. An MQ
// outage must degrade readiness while liveness stays UP — never a restart
// loop — and recovery must restore readiness. Named lab resources only.
// OPS-02: integration suite — needs the named lab PG + MQ; CI runs it explicitly.
@Tag("integration")
// Owns the shared broker during its outage drill: never run concurrently
// with other tests, even if parallelism is enabled later. Restores the QM
// in finally and proves recovery before returning.
@Isolated
@SpringBootTest(properties = {"queuedesk.publisher.enabled=false", "queuedesk.worker.enabled=false",
        "queuedesk.reconcile.enabled=false", "queuedesk.quarantine.enabled=false"})
@AutoConfigureMockMvc
class OpsHealthTest {
    @Autowired MockMvc mvc;
    @Test void livenessSurvivesMqOutageWhileReadinessFalls() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.mq.status").value("UP"));
        docker("stop", "qdesk-mq");
        try {
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
            mvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.components.mq.status").value("DOWN"));
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DOWN"))
                    .andExpect(jsonPath("$.components.mq.status").value("DOWN"));
        } finally {
            docker("start", "qdesk-mq");
            waitForQmgr();
        }
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.mq.status").value("UP"));
    }
    private static void docker(String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add("docker");
        command.addAll(Arrays.asList(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "docker timed out");
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }
    private static void waitForQmgr() throws Exception {
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
            var probe = new ProcessBuilder("docker", "exec", "qdesk-mq", "dspmq", "-m", "QDESK")
                    .redirectErrorStream(true).start();
            String output = new String(probe.getInputStream().readAllBytes());
            probe.waitFor(15, TimeUnit.SECONDS);
            if (output.contains("STATUS(Running)")) return;
            Thread.sleep(2_000);
        }
        fail("queue manager did not recover");
    }
}

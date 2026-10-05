package dev.queuedesk.http;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class InstructionApiTest {
    @Autowired MockMvc mvc;
    private final String body = """
        {"clientReference":"ref","runId":"test","senderParticipant":"SENDER_A",
        "receiverParticipant":"RECEIVER_B","amountMinor":12500,"currency":"USD"}
        """;
    @Test void admissionRepeatConflictAndRead() throws Exception {
        var response = mvc.perform(post("/api/v1/instructions").header("Idempotency-Key", "api-one")
                .contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.delivery").value("NOT_CONFIGURED")).andReturn().getResponse();
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
    }
}

package dev.queuedesk.http;

import dev.queuedesk.domain.*;
import dev.queuedesk.service.AdmissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/instructions")
public class InstructionController {
    private final AdmissionService service;
    public InstructionController(AdmissionService service) { this.service = service; }
    public record View(UUID instructionId, String status, Instant receivedAt, String statusUrl,
            String correlationId, String delivery, PaymentInstruction payload) {
        static View of(StoredInstruction i) {
            return new View(i.instructionId(), i.status(), i.receivedAt(), i.statusUrl(),
                    i.correlationId(), "NOT_CONFIGURED", i.payload());
        }
    }
    @PostMapping
    public ResponseEntity<View> submit(@RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation,
            @RequestBody PaymentInstruction payload) {
        var admission = service.admit(key, correlation, payload);
        var view = View.of(admission.instruction());
        return ResponseEntity.status(admission.repeated() ? 200 : 202)
                .location(URI.create(view.statusUrl())).header("X-Correlation-Id", view.correlationId()).body(view);
    }
    @GetMapping("/{id}")
    public View get(@PathVariable UUID id) { return View.of(service.get(id)); }
    @GetMapping
    public List<View> list(@RequestParam(required = false) String runId,
            @RequestParam(defaultValue = "50") int limit) {
        return service.list(runId, limit).stream().map(View::of).toList();
    }
}

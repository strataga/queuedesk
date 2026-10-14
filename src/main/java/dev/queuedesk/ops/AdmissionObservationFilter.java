package dev.queuedesk.ops;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * Observes admission traffic: opens the admission trace span and log scope,
 * and times each submission to its durable outcome.
 *
 * <p>Runs first ({@code HIGHEST_PRECEDENCE}) so timing covers the whole
 * request. Outcomes are recorded as counter and timer series tagged by
 * decision (accepted, repeated, conflict, invalid, capacity, error).
 * Error responses stay in their own series, never mixed into acceptance
 * latency. A monotonic clock measures the interval, and nothing except the
 * outcome decision leaves this filter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdmissionObservationFilter extends OncePerRequestFilter {
    private final QueuedeskMetrics metrics;
    private final QueuedeskTracer tracing;
    public AdmissionObservationFilter(QueuedeskMetrics metrics, QueuedeskTracer tracing) {
        this.metrics = metrics;
        this.tracing = tracing;
    }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The MDC scope covers every API call so pooled servlet threads never
        // inherit keys; metrics stay scoped to the admission POST below.
        boolean admission = "POST".equals(request.getMethod()) && "/api/v1/instructions".equals(request.getRequestURI());
        long start = System.nanoTime();
        try (var scope = admission
                ? LogContext.of("stage", "admission", "correlationId", request.getHeader("X-Correlation-Id"))
                : LogContext.of("correlationId", request.getHeader("X-Correlation-Id"))) {
            // The admission SERVER span is the trace root: its context commits
            // with the outbox row, so the whole instruction lifecycle shares
            // one trace even across threads and restarts.
            if (admission) {
                try (var span = tracing.server("POST /api/v1/instructions")) {
                    chain.doFilter(request, response);
                }
            } else {
                chain.doFilter(request, response);
            }
        } finally {
            if (admission) metrics.admission(decisionFor(response.getStatus()), System.nanoTime() - start);
        }
    }
    private static String decisionFor(int status) {
        return switch (status) {
            case 202 -> "accepted";
            case 200 -> "repeated";
            case 409 -> "conflict";
            case 400, 413 -> "invalid";
            case 503 -> "capacity";
            default -> "error";
        };
    }
}

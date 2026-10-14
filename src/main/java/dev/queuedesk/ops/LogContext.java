package dev.queuedesk.ops;

import org.slf4j.MDC;
import java.util.Map;

// MDC scopes for the JSON log contract (OBS-01): identity fields travel in
// the diagnostic context so every line inside a scope carries runId,
// instructionId, eventId, deliveryAttempt, correlationId, component and stage
// as real JSON fields — never interpolated only into the message. Scopes nest
// and restore the outer map on close; traceId/spanId join the same map once
// tracing is active (TEL-01). No bodies, memos, secrets or headers ever enter.
public final class LogContext {
    private LogContext() {}
    public static Scope of(String... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("MDC needs key/value pairs");
        Map<String, String> previous = MDC.getCopyOfContextMap();
        // A null value REMOVES the key: skipping would leak a stale outer
        // value (e.g. a null eventId inheriting the outer eventId) into logs
        // that claim to be unscoped. Close still restores the previous map.
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) MDC.put(pairs[i], pairs[i + 1]);
            else MDC.remove(pairs[i]);
        }
        return () -> {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        };
    }
    // Single put inside an open scope (outcome/reason at the moment they are
    // known); the enclosing scope's close still restores everything.
    public static void set(String key, String value) {
        if (value != null) MDC.put(key, value);
    }
    public interface Scope extends AutoCloseable {
        @Override void close();
    }
}

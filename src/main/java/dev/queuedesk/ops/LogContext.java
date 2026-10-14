package dev.queuedesk.ops;

import org.slf4j.MDC;
import java.util.Map;

/**
 * Scoped MDC (mapped diagnostic context) for the JSON log contract.
 *
 * <p>Identity fields travel in the diagnostic context so every log line
 * inside a scope carries runId, instructionId, eventId, deliveryAttempt,
 * correlationId, component, and stage as real JSON fields, never merely
 * interpolated into the message text. Scopes nest and restore the outer map
 * on close, which keeps pooled threads from inheriting stale identity.
 * Trace and span IDs join the same map while a span is active. Request
 * bodies, memos, secrets, and headers never enter the context.
 */
public final class LogContext {
    private LogContext() {}

    /**
     * Opens a scope from key/value pairs. A {@code null} value removes the
     * key for the scope's lifetime (see the leak note inside); closing
     * restores the previous map exactly.
     */
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
    /**
     * Sets one field inside an already-open scope (for values known only
     * later, such as outcome and reason). The enclosing scope's close still
     * restores everything. {@code null} leaves the map untouched.
     */
    public static void set(String key, String value) {
        if (value != null) MDC.put(key, value);
    }

    /** A live MDC scope; closing restores the map captured when it opened. */
    public interface Scope extends AutoCloseable {
        @Override void close();
    }
}

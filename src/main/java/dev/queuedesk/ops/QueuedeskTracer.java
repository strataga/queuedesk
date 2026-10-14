package dev.queuedesk.ops;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

// Manual spans on the one OTel SDK (TEL-01): admission (SERVER, in the
// filter), publish (PRODUCER, child of the stored outbox context), each
// worker attempt (CONSUMER, child of the JMS-extracted context), receiver
// calls (CLIENT), and reconciliation settles (child of the stored task
// context). Documented single-message parent/child model across the durable
// boundaries; redeliveries are siblings tagged with deliveryAttempt. Every
// activation also sets MDC traceId/spanId for the JSON logs.
@Component
public class QueuedeskTracer {
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier == null ? java.util.List.of() : carrier.keySet();
        }
    };
    private final Tracer tracer;
    private final OpenTelemetry openTelemetry;
    public QueuedeskTracer(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer("queuedesk", "0.1.0");
    }
    public SpanScope server(String name) {
        return start(name, SpanKind.SERVER, Context.root(), Map.of());
    }
    public SpanScope child(String name, SpanKind kind, String traceparent, String tracestate,
            Map<String, String> attrs) {
        if (traceparent == null) return start(name, kind, Context.root(), attrs);
        var carrier = new HashMap<String, String>();
        carrier.put("traceparent", traceparent);
        if (tracestate != null) carrier.put("tracestate", tracestate);
        // The SDK's composite propagator (trace + baggage), not trace-only:
        // extracting with a lone W3C propagator would silently drop baggage
        // the SDK was configured to carry.
        Context extracted = openTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.root(), carrier, GETTER);
        return start(name, kind, extracted, attrs);
    }
    public SpanScope internal(String name, Map<String, String> attrs) {
        return start(name, SpanKind.INTERNAL, Context.current(), attrs);
    }
    public SpanScope client(String name, Map<String, String> attrs) {
        return start(name, SpanKind.CLIENT, Context.current(), attrs);
    }
    private SpanScope start(String name, SpanKind kind, Context parent, Map<String, String> attrs) {
        var builder = tracer.spanBuilder(name).setParent(parent).setSpanKind(kind);
        attrs.forEach(builder::setAttribute);
        Span span = builder.startSpan();
        Scope scope = span.makeCurrent();
        var mdc = LogContext.of("traceId", span.getSpanContext().getTraceId(),
                "spanId", span.getSpanContext().getSpanId());
        return new SpanScope() {
            @Override
            public Span span() { return span; }
            @Override
            public void event(String name) { span.addEvent(name); }
            @Override
            public void close() {
                try {
                    scope.close();
                } finally {
                    try {
                        span.end();
                    } finally {
                        mdc.close();
                    }
                }
            }
        };
    }
    // W3C traceparent of the current span, or null outside tracing — stored
    // with the outbox row at admission so context survives dispatch + restart.
    public static String currentTraceparent() {
        var context = Span.current().getSpanContext();
        if (!context.isValid()) return null;
        return "00-" + context.getTraceId() + "-" + context.getSpanId()
                + "-" + (context.isSampled() ? "01" : "00");
    }
    public interface SpanScope extends AutoCloseable {
        Span span();
        void event(String name);
        @Override void close();
    }
}

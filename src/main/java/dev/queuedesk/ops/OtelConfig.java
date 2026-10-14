package dev.queuedesk.ops;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * The one telemetry owner: an explicitly built OpenTelemetry SDK.
 *
 * <p>Three span processors run on every span: a start counter (so created
 * spans can be compared against exported ones), the bounded in-memory ring
 * (synchronous and allocation-only), and the batched OTLP exporter behind
 * the counting wrapper. The batch queue is bounded at 2048 with short lab
 * delays; a dead collector yields counted export failures and never blocks
 * business work. Boot ships no tracing auto-configuration here, so nothing
 * is magic: every bound is visible below.
 */
@Configuration
public class OtelConfig {
    /** Assembles the SDK with W3C trace-context plus baggage propagation. */
    @Bean(destroyMethod = "close")
    public OpenTelemetrySdk openTelemetry(SdkTracerProvider tracerProvider) {
        return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(io.opentelemetry.context.propagation.TextMapPropagator
                        .composite(W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())))
                .build();
    }

    /** Builds the OTLP HTTP exporter (5-second timeout) behind its counting wrapper. */
    @Bean
    public CountingSpanExporter countingSpanExporter(TelemetryCounters counters,
            @Value("${queuedesk.telemetry.otlp-endpoint:http://localhost:4318}") String endpoint) {
        var otlp = OtlpHttpSpanExporter.builder().setEndpoint(endpoint)
                .setTimeout(Duration.ofSeconds(5)).build();
        return new CountingSpanExporter(otlp, counters);
    }

    /** Wires the three processors and stamps the resource as service {@code queuedesk}. */
    @Bean(destroyMethod = "close")
    public SdkTracerProvider tracerProvider(SpanRingBuffer ring, TelemetryCounters counters,
            CountingSpanExporter exporter) {
        return SdkTracerProvider.builder()
                .addSpanProcessor(countingStarts(counters))
                .addSpanProcessor(SimpleSpanProcessor.create(ring))
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(1, TimeUnit.SECONDS)
                        .setMaxQueueSize(2048)
                        .setMaxExportBatchSize(512)
                        .setExporterTimeout(5, TimeUnit.SECONDS)
                        .build())
                .setResource(Resource.getDefault().merge(
                        Resource.create(io.opentelemetry.api.common.Attributes.of(
                                io.opentelemetry.api.common.AttributeKey.stringKey("service.name"),
                                "queuedesk"))))
                .build();
    }
    private static SpanProcessor countingStarts(TelemetryCounters counters) {
        return new SpanProcessor() {
            @Override
            public void onStart(io.opentelemetry.context.Context parent,
                    io.opentelemetry.sdk.trace.ReadWriteSpan span) {
                counters.spanCreated();
            }
            @Override
            public void onEnd(io.opentelemetry.sdk.trace.ReadableSpan span) {
            }
            @Override
            public boolean isStartRequired() { return true; }
            @Override
            public boolean isEndRequired() { return false; }
        };
    }
}

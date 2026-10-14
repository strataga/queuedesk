package dev.queuedesk.ops;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.fieldnames.LogstashFieldNames;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

// OBS-01: the JSON contract plus MDC scope nesting. No Spring, no Docker:
// pure logging mechanics. The production logback.xml itself is loaded and
// its encoder pinned, so config drift fails the suite instead of the logs.
class LogFormatTest {
    @Test void productionLogbackKeepsJsonContract() throws Exception {
        var context = new LoggerContext();
        var configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure("src/main/resources/logback.xml");
        var appender = (OutputStreamAppender<ILoggingEvent>) context.getLogger("ROOT").getAppender("JSON");
        assertNotNull(appender, "logback.xml must keep the JSON appender");
        var encoder = (LogstashEncoder) appender.getEncoder();
        assertEquals("component", encoder.getFieldNames().getLogger());
        assertEquals("UTC", encoder.getTimeZone());
        context.stop();
    }
    @Test void jsonFieldsAndScopeNesting() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var fieldNames = new LogstashFieldNames();
        fieldNames.setLogger("component");
        var encoder = new LogstashEncoder();
        encoder.setContext(context);
        encoder.setFieldNames(fieldNames);
        encoder.setTimeZone("UTC");
        encoder.start();
        var appender = new OutputStreamAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(bytes);
        appender.start();
        var logger = context.getLogger("test.component");
        logger.addAppender(appender);
        logger.setAdditive(false);
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        try (var outer = LogContext.of("stage", "admission", "correlationId", "corr-1")) {
            try (var inner = LogContext.of("instructionId", "11111111-1111-1111-1111-111111111111",
                    "runId", "run-1", "eventId", "evt-1", "deliveryAttempt", "2", "outcome", "accepted")) {
                logger.info("admission.accepted");
            }
            assertNull(MDC.get("instructionId"), "inner scope must restore");
            assertEquals("corr-1", MDC.get("correlationId"));
        }
        assertNull(MDC.get("correlationId"), "outer scope must clear");
        appender.stop();
        logger.detachAppender(appender);
        String line = bytes.toString(StandardCharsets.UTF_8).trim();
        @SuppressWarnings("unchecked")
        var json = new tools.jackson.databind.ObjectMapper().readValue(line, Map.class);
        assertNotNull(json.get("@timestamp"));
        assertEquals("INFO", json.get("level"));
        assertEquals("test.component", json.get("component"));
        assertEquals("admission.accepted", json.get("message"));
        assertEquals("admission", json.get("stage"));
        assertEquals("corr-1", json.get("correlationId"));
        assertEquals("11111111-1111-1111-1111-111111111111", json.get("instructionId"));
        assertEquals("run-1", json.get("runId"));
        assertEquals("evt-1", json.get("eventId"));
        assertEquals("2", json.get("deliveryAttempt"));
        assertEquals("accepted", json.get("outcome"));
        String body = line;
        assertFalse(body.contains("12500"), "no payload values in logs");
    }
}

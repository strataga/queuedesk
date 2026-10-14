package dev.queuedesk.ops;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

// Bounded in-memory log ring (mockup 07 Logs tab): the last 500 log events
// of this process, oldest evicted first with a visible counter. Attached
// programmatically to the root logger (no logback.xml change): attach takes
// over any same-named appender so sequential test contexts never
// double-append, and detach runs on context close. Events
// carry timestamp/level/logger/message only — no stack traces (bounded, and
// traces leak file paths). Tests reset the ring per test.
@Component
public class LogRingBuffer {
    public record Entry(Instant at, String level, String logger, String message) {}
    private static final String APPENDER_NAME = "QDESK-RING";
    private static final int CAPACITY = 500;
    private static final int MAX_MESSAGE = 500;
    private final Deque<Entry> ring = new ArrayDeque<>();
    private long dropped;
    private AppenderBase<ILoggingEvent> appender;
    public synchronized void add(Instant at, String level, String logger, String message) {
        if (ring.size() >= CAPACITY) {
            ring.removeFirst();
            dropped++;
        }
        String bounded = message == null ? "" : message.substring(0, Math.min(message.length(), MAX_MESSAGE));
        ring.addLast(new Entry(at, level, logger, bounded));
    }
    public synchronized List<Entry> entries() { return new ArrayList<>(ring); }
    public synchronized long dropped() { return dropped; }
    public synchronized void resetForTest() { ring.clear(); dropped = 0; }
    public int capacity() { return CAPACITY; }
    @PostConstruct
    synchronized void attach() {
        var root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var stale = root.getAppender(APPENDER_NAME);
        if (stale != null) {
            // A previous context never detached (abnormal shutdown): take over
            // so this context's ring is the live one.
            root.detachAppender(stale);
            stale.stop();
        }
        appender = new RingAppender(this);
        appender.setName(APPENDER_NAME);
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
    }
    @PreDestroy
    synchronized void detach() {
        if (appender == null) return;
        var root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        appender.stop();
        appender = null;
    }
    static class RingAppender extends AppenderBase<ILoggingEvent> {
        private final LogRingBuffer target;
        RingAppender(LogRingBuffer target) { this.target = target; }
        @Override
        protected void append(ILoggingEvent event) {
            try {
                target.add(Instant.ofEpochMilli(event.getTimeStamp()), event.getLevel().toString(),
                        event.getLoggerName(), event.getFormattedMessage());
            } catch (RuntimeException dropped) {
                // Logging must never break the logged code.
            }
        }
    }
}

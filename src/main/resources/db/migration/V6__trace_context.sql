-- QueueDesk L3: trace context rides the outbox so it survives dispatch on
-- another thread and restart (thread-local context cannot). Stored at
-- admission from the HTTP server span; the publisher injects it into JMS
-- properties; nullable for rows admitted outside tracing (direct service
-- calls in tests). W3C traceparent/tracestate strings.
ALTER TABLE outbox ADD COLUMN traceparent TEXT;
ALTER TABLE outbox ADD COLUMN tracestate TEXT;

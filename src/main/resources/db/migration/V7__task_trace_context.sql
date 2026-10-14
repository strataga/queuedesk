-- QueueDesk L3: trace context rides the reconciliation task so the settle
-- span joins the instruction's original trace instead of starting a blind new
-- one. Stored at defer time from the attempt span; nullable for tasks whose
-- attempt ran outside tracing.
ALTER TABLE reconciliation_task ADD COLUMN traceparent TEXT;
ALTER TABLE reconciliation_task ADD COLUMN tracestate TEXT;

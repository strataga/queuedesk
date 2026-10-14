-- QueueDesk review hardening: index the access paths the repositories
-- actually query, and scope the reconciliation identity to OPEN rows.
-- Outbox due scan filters published_at IS NULL + next_due_at <= now() and
-- the deferred-head subquery takes MIN(outbox_id) over next_due_at > now():
-- one composite partial index serves both instead of (outbox_id) alone.
DROP INDEX IF EXISTS outbox_due_idx;
CREATE INDEX outbox_due_idx ON outbox (next_due_at, outbox_id) WHERE published_at IS NULL;
-- Quarantine reads scan the unresolved set ordered by recency; resolveExcept
-- also sweeps resolved_at IS NULL. Partial index on the open set.
CREATE INDEX IF NOT EXISTS quarantine_record_open_idx
    ON quarantine_record (last_seen_at DESC) WHERE resolved_at IS NULL;
-- One OPEN task per instruction — but DONE history must never block a later
-- task when the same instruction goes uncertain again (reissue after a
-- resolved deferral). Scope the identity to OPEN rows. Dropping the UNIQUE
-- constraint also drops its backing index, so the instruction_id FK gets an
-- explicit index (Postgres never auto-indexes FK columns).
ALTER TABLE reconciliation_task DROP CONSTRAINT reconciliation_task_instruction_id_key;
CREATE UNIQUE INDEX reconciliation_task_open_idx
    ON reconciliation_task (instruction_id) WHERE status = 'OPEN';
CREATE INDEX IF NOT EXISTS reconciliation_task_instruction_idx
    ON reconciliation_task (instruction_id);

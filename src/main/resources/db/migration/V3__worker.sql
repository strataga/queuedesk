-- QueueDesk L2 worker: dedup key, attempt telemetry, outbox error evidence.
-- processed_message.event_id is the primary dedup key: one row per logically
-- handled event, inserted in the SAME transaction as the business outcome.
-- processing_attempt is telemetry only, never business truth; rows written in a
-- rolled-back transaction vanish, so failure evidence after rollback uses a
-- separate controlled write (see ProcessingService/WorkerListener).
CREATE TABLE processed_message (
    event_id TEXT PRIMARY KEY,
    instruction_id UUID NOT NULL REFERENCES instruction (instruction_id) ON DELETE RESTRICT,
    handled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    outcome_reference TEXT NOT NULL
);
CREATE INDEX processed_message_instruction_idx ON processed_message (instruction_id);

CREATE TABLE processing_attempt (
    attempt_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instruction_id UUID REFERENCES instruction (instruction_id) ON DELETE RESTRICT,
    event_id TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at TIMESTAMPTZ,
    result TEXT NOT NULL CHECK (result IN
        ('SUCCESS', 'BUSINESS_REJECT', 'DUPLICATE_SKIPPED', 'FAILED', 'UNSUPPORTED')),
    reason TEXT
);
CREATE INDEX processing_attempt_instruction_idx ON processing_attempt (instruction_id);
CREATE INDEX processing_attempt_event_idx ON processing_attempt (event_id);

-- OUT-02 backfill: the publisher records the last send failure per outbox row.
ALTER TABLE outbox ADD COLUMN last_error TEXT;

-- QueueDesk L3: quarantine projection + per-attempt provider delivery counts.
-- quarantine_record is an OBSERVATION of the backout queue, never business
-- truth: the MQ move and this projection are not atomic (D-05). Identity is the
-- sha256 of the raw body, so malformed messages without an eventId stay
-- identifiable without fabricating a business instruction.
CREATE TABLE quarantine_record (
    quarantine_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_digest TEXT NOT NULL UNIQUE,
    event_id TEXT,
    instruction_id UUID REFERENCES instruction (instruction_id) ON DELETE RESTRICT,
    reason TEXT NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);
CREATE INDEX quarantine_record_event_idx ON quarantine_record (event_id);
CREATE INDEX quarantine_record_instruction_idx ON quarantine_record (instruction_id);

-- Singleton reconciler heartbeat: reads report staleness from here, so a dead
-- reconciler (or an unreachable broker) never masquerades as "zero quarantine".
CREATE TABLE quarantine_projection_meta (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    last_reconciled_at TIMESTAMPTZ,
    last_error TEXT
);
INSERT INTO quarantine_projection_meta (singleton) VALUES (TRUE);

-- RETRY-01: the provider's delivery count per attempt, beside the app counter.
ALTER TABLE processing_attempt ADD COLUMN delivery_count INT;
-- App-owned routing outcomes: the move itself, and its terminal failure.
ALTER TABLE processing_attempt DROP CONSTRAINT IF EXISTS processing_attempt_result_check;
ALTER TABLE processing_attempt ADD CONSTRAINT processing_attempt_result_check CHECK (result IN
    ('SUCCESS', 'BUSINESS_REJECT', 'DUPLICATE_SKIPPED', 'FAILED', 'UNSUPPORTED',
     'ROUTED', 'ROUTE_FAILED'));

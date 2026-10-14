-- QueueDesk L2: outbox for durable publish intent. One row per InstructionReceived
-- event; published_at NULL means still due. Partial index keeps due scans small.
CREATE TABLE outbox (
    outbox_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id TEXT NOT NULL UNIQUE,
    instruction_id UUID NOT NULL REFERENCES instruction (instruction_id) ON DELETE RESTRICT,
    event_type TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    publish_attempts INT NOT NULL DEFAULT 0,
    next_due_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX outbox_instruction_idx ON outbox (instruction_id);
CREATE INDEX outbox_due_idx ON outbox (outbox_id) WHERE published_at IS NULL;

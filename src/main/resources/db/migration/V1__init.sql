-- QueueDesk L1: durable instruction admission with an atomic audit trail.
-- Conventions: snake_case, TEXT with length CHECKs, TIMESTAMPTZ, explicit FK index.

CREATE TABLE instruction (
    instruction_id UUID PRIMARY KEY,
    idempotency_key TEXT NOT NULL UNIQUE CHECK (char_length(idempotency_key) BETWEEN 1 AND 80),
    request_hash TEXT NOT NULL,
    run_id TEXT NOT NULL CHECK (char_length(run_id) BETWEEN 1 AND 80),
    client_reference TEXT NOT NULL CHECK (char_length(client_reference) BETWEEN 1 AND 80),
    sender_participant TEXT NOT NULL,
    receiver_participant TEXT NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor BETWEEN 1 AND 10000000),
    currency TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'RECEIVED'
        CHECK (state IN ('RECEIVED', 'BUSINESS_ACCEPTED', 'REJECTED', 'COMPLETED_SIMULATED', 'PENDING_CONFIRMATION')),
    reason TEXT,
    correlation_id TEXT NOT NULL CHECK (char_length(correlation_id) BETWEEN 1 AND 80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX instruction_run_id_idx ON instruction (run_id);
CREATE INDEX instruction_state_idx ON instruction (state);
CREATE INDEX instruction_created_id_idx ON instruction (created_at, instruction_id);

CREATE TABLE instruction_audit (
    audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instruction_id UUID NOT NULL REFERENCES instruction (instruction_id) ON DELETE RESTRICT,
    old_state TEXT,
    new_state TEXT NOT NULL,
    reason TEXT,
    actor TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX instruction_audit_instruction_idx ON instruction_audit (instruction_id);

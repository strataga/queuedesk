-- QueueDesk L3 uncertainty: independent receiver truth + durable reconcile tasks.
-- receiver_result is written by the gateway in its OWN transaction (D-01): one
-- row per instructionId, repeat calls return it, calls counted as evidence.
-- Outcome PENDING means stored-but-unresolved (reissue resumes it); UNKNOWN
-- means the receiver genuinely does not know (reconcile keeps waiting).
CREATE TABLE receiver_result (
    instruction_id UUID PRIMARY KEY REFERENCES instruction (instruction_id) ON DELETE RESTRICT,
    outcome TEXT NOT NULL CHECK (outcome IN ('PENDING', 'COMPLETED', 'REJECTED', 'UNKNOWN')),
    reason TEXT,
    calls INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One open task per uncertain instruction; claimed with SKIP LOCKED so a
-- future second poller cannot double-resolve. Due/attempts persist: restart
-- resumes, and elapsed time alone never terminates anything.
CREATE TABLE reconciliation_task (
    task_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instruction_id UUID NOT NULL UNIQUE REFERENCES instruction (instruction_id) ON DELETE RESTRICT,
    event_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DONE')),
    attempts INT NOT NULL DEFAULT 0,
    next_due_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX reconciliation_due_idx ON reconciliation_task (next_due_at) WHERE status = 'OPEN';

-- Reconciliation evidence: PENDING marks the worker's deferral, RECONCILED the
-- poller's definitive resolution (never a reissued business effect).
ALTER TABLE processing_attempt DROP CONSTRAINT IF EXISTS processing_attempt_result_check;
ALTER TABLE processing_attempt ADD CONSTRAINT processing_attempt_result_check CHECK (result IN
    ('SUCCESS', 'BUSINESS_REJECT', 'DUPLICATE_SKIPPED', 'FAILED', 'UNSUPPORTED',
     'ROUTED', 'ROUTE_FAILED', 'PENDING', 'RECONCILED'));

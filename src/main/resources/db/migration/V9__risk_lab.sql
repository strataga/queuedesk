-- QueueDesk B04 risk lab (RISK-01..06): assessments, attempt leases, audited
-- reviews. Conventions: snake_case, TEXT+CHECKs, TIMESTAMPTZ, explicit FK index.
-- No seed data, no credential columns, no account fields anywhere here.

-- RISK_HELD joins the business-state CHECK (PRD §6).
ALTER TABLE instruction DROP CONSTRAINT instruction_state_check;
ALTER TABLE instruction ADD CONSTRAINT instruction_state_check CHECK (state IN
  ('RECEIVED', 'BUSINESS_ACCEPTED', 'REJECTED', 'COMPLETED_SIMULATED',
   'PENDING_CONFIRMATION', 'RISK_HELD'));

-- One assessment per (instruction, frozen versions). Reuse under frozen versions;
-- never silently re-score after a model change (PRD §24).
CREATE TABLE risk_assessment (
  assessment_id UUID PRIMARY KEY,
  instruction_id UUID NOT NULL REFERENCES instruction (instruction_id) ON DELETE RESTRICT,
  feature_version TEXT NOT NULL,
  question_version TEXT NOT NULL,
  policy_version TEXT NOT NULL,
  input_hash TEXT NOT NULL,
  feature_snapshot JSONB NOT NULL,
  evaluation_status TEXT NOT NULL CHECK (evaluation_status IN
    ('OK', 'UNKNOWN', 'NOT_APPLICABLE', 'TIMEOUT', 'INVALID_RESPONSE', 'PROVIDER_UNAVAILABLE')),
  policy_result TEXT NOT NULL CHECK (policy_result IN
    ('ALLOW', 'REVIEW', 'NOT_ASSESSED', 'REJECT')),
  reason_codes TEXT[] NOT NULL DEFAULT '{}',
  signals JSONB,
  provider_latency_ms BIGINT,
  assessed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT risk_assessment_identity UNIQUE (instruction_id, feature_version, question_version, policy_version)
);
CREATE INDEX risk_assessment_instruction_idx ON risk_assessment (instruction_id);
CREATE INDEX risk_assessment_result_idx ON risk_assessment (policy_result, assessed_at);

-- Durable attempt lease: prevents concurrent provider calls for one instruction
-- (PRD §24). Status OPEN while a call is in flight; DONE/EXPIRED after.
CREATE TABLE risk_assessment_attempt (
  instruction_id UUID NOT NULL REFERENCES instruction (instruction_id) ON DELETE RESTRICT,
  feature_version TEXT NOT NULL,
  question_version TEXT NOT NULL,
  policy_version TEXT NOT NULL,
  lease_owner TEXT NOT NULL,
  lease_until TIMESTAMPTZ NOT NULL,
  status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DONE', 'EXPIRED')),
  PRIMARY KEY (instruction_id, feature_version, question_version, policy_version)
);

-- Audited review fixture (resume path only; operator buttons out of scope).
-- No timeout/score/review ever creates a replacement instruction.
CREATE TABLE risk_review (
  review_id UUID PRIMARY KEY,
  instruction_id UUID NOT NULL REFERENCES instruction (instruction_id) ON DELETE RESTRICT,
  actor TEXT NOT NULL,
  decision TEXT NOT NULL CHECK (decision IN ('RESUME', 'REJECT')),
  reason TEXT NOT NULL,
  continuation_event_id TEXT,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX risk_review_instruction_idx ON risk_review (instruction_id);

-- Gate evidence: risk holds are attempt telemetry like any other outcome.
ALTER TABLE processing_attempt DROP CONSTRAINT IF EXISTS processing_attempt_result_check;
ALTER TABLE processing_attempt ADD CONSTRAINT processing_attempt_result_check CHECK (result IN
    ('SUCCESS', 'BUSINESS_REJECT', 'DUPLICATE_SKIPPED', 'FAILED', 'UNSUPPORTED',
     'ROUTED', 'ROUTE_FAILED', 'PENDING', 'RECONCILED', 'RISK_HELD'));

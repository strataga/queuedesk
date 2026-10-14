-- V10: metered provider usage on assessments (live Jev gate, PRD §25).
-- Nullable: the mock never spends, and pre-gate rows predate metering.
-- The $1-cap ledger is the SUM over these columns — durable across restarts.
ALTER TABLE risk_assessment ADD COLUMN input_tokens BIGINT;
ALTER TABLE risk_assessment ADD COLUMN output_tokens BIGINT;

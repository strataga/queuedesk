# RUNBOOK-RISK — B04 fraud-risk lab (live Jev gate, $1 cap)

Mock-first L5 lab: the pre-clearing risk gate, the held-out RS01–RS06 harness,
and the read-only dashboard endpoints. The live Jev provider is wired behind
an explicit human approval (6 Oct 2026, $1 cap): no live call happens unless
the operator boots `RULES_PLUS_JEV` with a credential present.

## Modes

`queuedesk.risk.mode` (env `QUEUEDESK_RISK_MODE`, default `OFF`):

- `OFF` — base flow untouched (gate short-circuits).
- `RULES_ONLY` — deterministic rules only; assessments record
  `NOT_APPLICABLE` with null signals.
- `RULES_PLUS_MOCK` — rules plus the deterministic mock provider.
- `RULES_PLUS_JEV` — rules plus the live Jev provider (approval + $1 cap).
  Without a credential the boot fails with a named error — never a silent
  mock fallback, never a live call without a key.

Supporting config: `queuedesk.risk.{feature,question,policy}-version`
(`1.0`/`mock-2026-10`/`0.4`), `provider-deadline-ms` (50..30000, default 2000),
`velocity-window-minutes` (1..1440, default 60), `mock-fault`
(`NONE|TIMEOUT|INVALID|UNAVAILABLE|UNKNOWN`, default `NONE`),
`queuedesk.risk.jev.budget-usd` (0.01..10000, default 1.0).

## Live Jev adapter (wired, metered)

`JevRiskSignalProvider` implements the same `RiskSignalProvider` contract as
the mock (same Q01/Q02 Choice questions, same shared `validate()`), so policy
code never changes. `RiskLiveConfig` registers it as the `@Primary` provider
bean in `RULES_PLUS_JEV` mode only; every other mode leaves that config
inert and the mock stays the sole provider. The credential resolves from
`QUEUEDESK_RISK_JEV_API_KEY` first, then `.cache/jev-secrets/apiKey`
(chmod 600, gitignored, never logged, never displayed). `JevProperties` pins
the model (`jev-1.13.0`, never the `jev-latest` alias), requires https
outside loopback, and redacts the key from `toString`. Wire shape follows
the TypeSafe API reference, confirmed on the live wire 6 Oct 2026 (POST
`{base}/v1/systemone`, Bearer [REDACTED], `{state, model, questions}` →
`{model, answers, usage}`); status mapping is 401 → unavailable (check key),
422 → invalid response, 429/529 → unavailable with no automatic retry,
timeouts → timeout.

Live-wire finding: the model reports confidence independently of the picked
probability (Q02 confidence 0.49 beside P(false)=0.74) — shared validation
requires only finite [0,1] confidence, since the policy never reads it.

Spend ledger (PRD §25): every Jev success persists its token usage
(V10 `input_tokens`/`output_tokens`); `usageTotals()` SUMs the durable rows
so restarts never reset the cap, the adapter refuses new calls as
unavailable once spent ≥ budget (gate holds NOT_ASSESSED), and
`GET /api/v1/risk/summary` carries `usage` (rendered as the risk panel's
spend strip with an exhausted alert). Bounds to know: concurrent in-flight
calls can overshoot by ~1 call each (~$0.00002 at list price); failed and
out-of-band calls spend Jev-side budget the ledger cannot see; and
`reset-lab` TRUNCATEs CASCADE into `risk_assessment`, so a deliberate reset
zeroes the ledger — durability covers restarts, not resets.

Remaining eval work (no more wiring): held-out RULES_ONLY-vs-JEV eval
(confusion matrix, calibration per question) and latency/cost review.

## Policy cheat sheet

Rules evaluate first, always — even when the provider faults (the fault is
recorded in `evaluationStatus`; the rule match is never hidden):

- `NEW_RECIPIENT` (receiver unseen in this run) or `UNUSUAL_AMOUNT` (amount ≥
  5x the sender's run median) → `REVIEW` hold. First-in-run always holds.
- Near-cap amount (≥ 9,500,000) to a new recipient → `REJECT` under the
  explicit simulation block. Never from a score.
- Quiet traffic + provider OK → `ALLOW`. Quiet + fault/abstention →
  `NOT_ASSESSED` hold. Confidence labels the exact question; nothing here is a
  "probability of fraud".
- A live attempt lease (another evaluation in flight, or a crash inside its
  TTL) → visible `NOT_ASSESSED`/`UNKNOWN` hold without calling the provider
  (conflict-hold; transient retries would burn toward backout while the lease
  outlives them).

Features scope to the run and to strictly older rows: identical (seed, count)
fixtures compute identical features regardless of worker timing, and held-out
suites cannot leak velocity across runs.

## Operating

- Migration `V9__risk_lab.sql` adds `risk_assessment`,
  `risk_assessment_attempt`, `risk_review`, the `RISK_HELD` state, and the
  `RISK_HELD` attempt result; `V10__jev_usage.sql` adds the per-assessment
  token columns for the spend ledger. Additive; older builds keep working.
- Review actions are deferred: `POST /api/v1/risk/reviews` answers 404
  `NOT_FOUND` until B01. Resume/reject exist as service methods only
  (`RiskReviewService`), reusing the original instructionId via a continuation
  event — never a replacement instruction.
- Harness: `./mvnw -q package -DskipTests`, then
  `scripts/risk-eval.py [rs1|rs2|rs3|rs4|rs5|rs6|all]` (needs `JAVA_HOME`,
  `./scripts/postgres up`, `./scripts/mq up` + `setup`). It boots its own app
  on :18082 against the `queuedesk_risk` database and the
  `QUEUEDESK.RISK.EVAL.*` queues, so it never races the live backend.
  Reports land in `reports/rs<N>-<pid>-<ts>.json`; labels stay in
  `scripts/risk-oracle.json` and never enter scorer input.
- `RiskGateTest` owns the `queuedesk_risk` database (self-provisioned) for the
  same reason: the shared `queuedesk` database is also consumed by the live
  backend, whose older build would poison `RISK_HELD` rows.
- Crash drill: `QUEUEDESK_FAULT_LAB_ARMED=1` plus
  `QUEUEDESK_FAULT_RISK_HALT_AFTER_ASSESSMENT=true` halts (exit 99) after the
  assessment row saves; the reboot's redelivery commits the single
  `NOT_ASSESSED` hold (RS06).

## Diagnosis

- `GET /api/v1/risk/summary`, `/api/v1/risk/assessments?...`, and
  `/api/v1/risk/assessments/{instructionId}` explain holds (rules, signals,
  versions, timeline). Instruction reads carry `riskStatus`/`assessmentId`/
  `riskReasonCodes`.
- `GET /api/v1/telemetry/summary` separates admission/completion/provider p95;
  `riskProvider` is null-valued with `"mode":"OFF"` when the gate is off.
- Ops latency (meters, p95) is never accuracy: precision/recall come only from
  the labeled RS reports (TG04).

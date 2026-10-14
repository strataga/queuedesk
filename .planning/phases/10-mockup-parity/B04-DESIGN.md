# B04 Build Design — Mock-First Jev Fraud-Risk Lab + New Dashboard Endpoints

**Status:** design only (research task, no code changed).
**Scope guard:** mock-first ONLY — modes OFF / RULES_ONLY / RULES_PLUS_MOCK. No live
provider, no credentials, no account, no Jev HTTP adapter. The `astra-jev` skill was
requested once via `read_skill` and returned `disabled-skill`; this design proceeds
mock-first and the RULES_PLUS_JEV adapter stays out of scope behind the B04 live gate
(`.planning/BACKLOG.md:56`).
**Source of truth:** PRD `docs/QueueDesk_PRD.md` §§15, 17A, 23–25; B04 entry
`.planning/BACKLOG.md:52-66`; UI mockups `docs/images/ui/*.png` (design concepts —
illustrative counts, invented CLI flags; see §8).

## 1. Requirement IDs (new; existing 64 untouched, no renumbering)

Per `.planning/REQUIREMENTS.md:198-204` there are 64 roadmap requirements; PR13–PR16 /
RS01–RS06 / TG04 already exist as deferred source IDs owned by B04
(`.planning/REQUIREMENTS.md:112-113`). This design adds **detail IDs only**:

| ID | Covers | PRD anchor |
|----|--------|------------|
| RISK-01 | Feature snapshot + versions + missingness | §24 (`QueueDesk_PRD.md:665`) |
| RISK-02 | RiskSignalProvider contract + OFF/RULES_ONLY/RULES_PLUS_MOCK modes | §23–24 (`:652-659`, `:679`) |
| RISK-03 | Java policy composition + atomic hold commit, never call receiver on hold | §24 (`:681-688`) |
| RISK-04 | Attempt lease, no open tx during provider call, reuse/no-silent-rescore, continuation event | §24 (`:688-690`) |
| RISK-05 | Held-out harness + RS01–RS06 reports | §25 (`:696-707`) |
| RISK-06 | Risk metrics/ops (hold counts, provider p95/p99, call counts) separated from accuracy | §17 (`:463`), §17A TG04 (`:502`) |
| DASH-01 | Queues page endpoints (outbox/MQ/quarantine freshness) | §15 (`:402`), mockup 06 |
| DASH-02 | Telemetry page endpoints (latency cards, series, sampled traces, exporter health) | §17A (`:485-493`), mockup 07 |
| DASH-03 | Trace-detail endpoint (waterfall spans by traceId) | §17A (`:479-481`), mockup 08 |
| DASH-04 | Scenario catalog + run-report endpoints with REAL CLI previews | §14–15, mockups 09/10 |
| DASH-05 | Risk UI endpoints (held list, assessment detail, summary; read-only) | §15 (`:404`), mockups 04/05 |
| DASH-06 | Instruction reads extended with riskStatus/assessmentId/riskReasonCodes | §7 (`:205`) |

## 2. Database — single new Flyway migration V9

One migration only: `src/main/resources/db/migration/V9__risk_lab.sql`.
Existing chain is V1–V8 (`V1__init.sql` … `V8__review_indexes.sql`); table/column
conventions follow V1–V3 (snake_case, TEXT+CHECKs, TIMESTAMPTZ, explicit FK indexes).

```sql
-- RISK_HELD joins the business-state CHECK (PRD §6, QueueDesk_PRD.md:172).
ALTER TABLE instruction DROP CONSTRAINT instruction_state_check;
ALTER TABLE instruction ADD CONSTRAINT instruction_state_check CHECK (state IN
  ('RECEIVED','BUSINESS_ACCEPTED','REJECTED','COMPLETED_SIMULATED',
   'PENDING_CONFIRMATION','RISK_HELD'));

-- One assessment per (instruction, frozen versions). Reuse under frozen versions;
-- never silently re-score after a model change (PRD §24, :688).
CREATE TABLE risk_assessment (
  assessment_id UUID PRIMARY KEY,
  instruction_id UUID NOT NULL REFERENCES instruction (instruction_id) ON DELETE RESTRICT,
  feature_version TEXT NOT NULL,
  question_version TEXT NOT NULL,
  policy_version TEXT NOT NULL,
  input_hash TEXT NOT NULL,
  feature_snapshot JSONB NOT NULL,          -- amount, velocity, elapsed, receiver history, as-of ts, missingness flags
  evaluation_status TEXT NOT NULL CHECK (evaluation_status IN
    ('OK','UNKNOWN','NOT_APPLICABLE','TIMEOUT','INVALID_RESPONSE','PROVIDER_UNAVAILABLE')),
  policy_result TEXT NOT NULL CHECK (policy_result IN
    ('ALLOW','REVIEW','NOT_ASSESSED','REJECT')),
  reason_codes TEXT[] NOT NULL DEFAULT '{}', -- predefined codes only, never model prose
  signals JSONB,                             -- typed answers (Choice/Noul), versions, latency; NULL in RULES_ONLY
  provider_latency_ms BIGINT,
  assessed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT risk_assessment_identity UNIQUE (instruction_id, feature_version, question_version, policy_version)
);
CREATE INDEX risk_assessment_instruction_idx ON risk_assessment (instruction_id);
CREATE INDEX risk_assessment_result_idx ON risk_assessment (policy_result, assessed_at);

-- Durable attempt lease: prevents concurrent provider calls for one instruction
-- (PRD §24, :688). status OPEN while a call is in flight; DONE/EXPIRED after.
CREATE TABLE risk_assessment_attempt (
  instruction_id UUID NOT NULL REFERENCES instruction (instruction_id) ON DELETE RESTRICT,
  feature_version TEXT NOT NULL, question_version TEXT NOT NULL, policy_version TEXT NOT NULL,
  lease_owner TEXT NOT NULL, lease_until TIMESTAMPTZ NOT NULL,
  status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','DONE','EXPIRED')),
  PRIMARY KEY (instruction_id, feature_version, question_version, policy_version)
);

-- Audited review fixture (resume path only; operator buttons out of scope, :690).
-- No timeout/score/review ever creates a replacement instruction.
CREATE TABLE risk_review (
  review_id UUID PRIMARY KEY,
  instruction_id UUID NOT NULL REFERENCES instruction (instruction_id) ON DELETE RESTRICT,
  actor TEXT NOT NULL, decision TEXT NOT NULL CHECK (decision IN ('RESUME','REJECT')),
  reason TEXT NOT NULL, continuation_event_id TEXT,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX risk_review_instruction_idx ON risk_review (instruction_id);
```

No seed data, no credential columns, no account fields anywhere in V9.

## 3. Domain + provider contract (RISK-01, RISK-02)

- `risk/FeatureSnapshot` (record, plain Java, unit-tested without Spring — SK01 pattern):
  `amountMinor`, `priorUniqueCount(window)`, `elapsedSincePrevious`, `receiverSeenBefore`,
  `amountToBaselineRatio`, `asOf` (frozen Instant), `featureVersion`, explicit
  `missing*` booleans. Velocity counts **unique instructions only** — retries and
  transport duplicates excluded (PRD §24, :665). Source queries hit `instruction`
  (run/participant/amount/created_at) and `processed_message` (dedup truth), never
  `JMSMessageID` (cf. `WorkerListener.java:73-75`).
- `risk/RiskSignalProvider` interface returning the application contract (PRD §24, :679):
  `assessmentId, inputHash, providerVersion, modelVersion, questionVersion, signals[]`
  (typed: Choice{questionId, choice, probabilities, confidence} / Noul{questionId, noul}),
  `latencyMs, evaluationStatus`. Java-first validation: exact question IDs/types, allowed
  keys, finite ranges, distribution totals (mock still validates — proves the adapter
  path for PR13 swaps: success / uncertain / failed providers, :654).
- Modes (`queuedesk.risk.mode`, default `OFF`): `OFF` (original flow — base app keeps
  working, :642), `RULES_ONLY` (baseline, no provider call, `signals=NULL`),
  `RULES_PLUS_MOCK` (deterministic mock provider; seeded, bounded latency). There is no
  fourth enum value in this build — RULES_PLUS_JEV must fail config validation with a
  named error, never silently degrade (PRD §23, :659: "never silently switch modes").

## 4. Policy composition (RISK-03)

`risk/RiskPolicy` — pure Java, owns ALLOW/REVIEW/NOT_ASSESSED/REJECT (:681-686):

- Deterministic rules evaluated first (exact predicates over the snapshot, e.g.
  `NEW_RECIPIENT`, `UNUSUAL_AMOUNT` per mockup 05). Any matched rule → REVIEW.
- Provider signals consulted only in RULES_PLUS_MOCK: TIMEOUT/INVALID/UNAVAILABLE/
  UNKNOWN required signal → NOT_ASSESSED → hold. Mock answers are context; matched
  rules drive the illustrative policy (mockup 05 caption).
- REJECT only under an explicit deterministic simulation policy with a reason; never
  from a score alone. Confidence is labeled per exact question; UI must never render
  "probability of fraud" (:675, :404).
- Metrics: `queuedesk.risk.holds.total{result}`, `queuedesk.risk.provider.duration`
  (p95/p99 via the percentile config pattern in `application.properties:52-53`),
  `queuedesk.risk.provider.calls.total{status}` — fixed small tag sets, no ID labels
  (cf. `QueuedeskMetrics.java:13-18`). Hold/not-assessed counts are operational;
  precision/recall come only from the labeled harness (RISK-06, :463, TG04 :502).

## 5. Worker gate placement (RISK-03, RISK-04)

Current single-tx shape: `ProcessingService.processInTransaction` does
dedup-check → clearing → receiver in one `TransactionTemplate`
(`ProcessingService.java:58-60,95-143`); JMS commits after return
(`WorkerListener.java:108-115`). The gate inserts **between the dedup check
(`ProcessingService.java:103-109`) and clearing (`:110`)**, but PRD §24 forbids
holding a DB transaction open during provider HTTP (:688), and PRD §11 requires the
read-tx to close before provider work with a recheck in the final tx (:295). So:

1. **Read tx** (new short tx in `RiskGateService.evaluate`, called from the listener
   path before `processing.process` when mode != OFF): load instruction, compute +
   persist feature snapshot, acquire `risk_assessment_attempt` lease
   (`INSERT … ON CONFLICT DO NOTHING`; conflict → skip call, reuse completed
   assessment or hold NOT_ASSESSED if none).
2. **Provider call outside any tx**, inside span `queuedesk.risk.evaluate`
   (span-name pattern cf. `queuedesk.worker.attempt`, `WorkerListener.java:80`;
   PRD §17A requires a risk-evaluation span, :481). Bounded deadline; timeout →
   NOT_ASSESSED.
3. **Final tx**: recheck the unique `processed_message` marker (redelivery may have
   landed meanwhile), then commit hold (`RECEIVED → RISK_HELD` + audit row +
   `processed_message` + assessment row) **atomically** (:688), or REJECT
   (`RECEIVED → REJECTED`), or ALLOW → fall through to the existing
   `ProcessingService.process` path unchanged.
4. `BusinessState` gains `RISK_HELD` with `RECEIVED → {…, RISK_HELD}`,
   `RISK_HELD → {RECEIVED (audited resume), REJECTED}` (PRD §6, :172); NOT_ASSESSED
   never auto-allows on expiry — an unfinished expired attempt becomes a visible
   NOT_ASSESSED hold (:688).
5. Receiver is never called on hold (PR14, :654); `move()` keeps throwing
   `UnsupportedEnvelope` on illegal transitions (`ProcessingService.java:156-164`).
6. Audited resume reuses the original `instructionId` via a new deduplicated
   continuation event through the outbox (distinct eventType, same instructionId —
   DATA-02 pattern, :266, :690); no replacement payment intent.

## 6. Review-action API — DEFERRED (B01 gate)

The UI stays read-only: mockup 04 states "Review workflow deferred … Actions are
outside this design concept", PRD §15 defers all controls pending a separate
authority/audit design (:414-416), and B01's entry gate requires it
(`BACKLOG.md:9`). **This build exposes no POST/PATCH/DELETE.** The future contract
(reserved, not implemented) is:

```text
POST /api/v1/risk/reviews            (DEFERRED — 404 until B01 design lands)
Idempotency-Key: <uuid>              (same semantics as POST /instructions, PRD §7)
{ "instructionId": "…", "decision": "RESUME|REJECT", "reason": "…", "actor": "…" }
→ 202 { reviewId, instructionId, decision, continuationEventId } + audit row
```

Errors reuse `ApiErrors` shapes (`ApiErrors.java:21-54`): 400 `INVALID_REQUEST`,
404 `NOT_FOUND`, 409 `IDEMPOTENCY_CONFLICT`, 503 `UNAVAILABLE`.

## 7. RS01–RS06 harness plan (RISK-05)

Same shape as the SC harness (`scripts/scenarios.py:98-325` + `Generate.writeReport`,
`tools/Generate.java:382-416`): a new stdlib-only `scripts/risk-eval.py` boots the
packaged app on a scratch port, runs fixed-seed suites, asserts, and writes
`reports/rs<N>-<pid>-<ts>.json` with the same envelope keys
(`tool/fixtureVersion/scenario/seed/runId/…/assertionFailures`, cf.
`reports/sc01-24232-1791220056.json`).

- **Oracle isolation:** labels live in `scripts/risk-oracle.json` (evaluator-only);
  the scorer reads feature snapshots + mock signals only — labels, scenario names
  revealing labels, and future outcomes never enter scorer input (:696, :363).
- **Disjoint fixtures:** dev / calibration / final-test seed sets are disjoint with
  disjoint synthetic actor histories; the final set freezes before thresholds are
  chosen (:696). New `Generate --scenario risk-<n>` fixtures are additive to the
  existing eight (`tools/Generate.java:73`) and reuse the same bounds/allowlist.
- **Per-RS assertions:** RS01 benign hold/reject rates reported (unusual ≠ fraud);
  RS02 rules-vs-missed-suspicious; RS03 retries/redeliveries show stable velocity +
  cached assessment reuse; RS04 memo injection cannot alter policy/labels/
  destinations; RS05 provider faults → NOT_ASSESSED holds, bounded calls, receiver
  `calls` stays 0 (query pattern cf. `scenarios.py:226`); RS06 restart around
  assessment-save/hold-commit → no second outcome, incomplete attempt visible.
- **Report additions:** confusion matrix, false-alarm rate, missed-suspicious rate,
  precision/recall, hold rate, unassessed coverage, provider p95/p99 — holds kept
  separate from definitive classifications (:707-709). RULES_PLUS_MOCK validates the
  harness only; it proves no Jev accuracy (:707).

## 8. REAL CLI command template for previews (mockups invented flags)

The mockups' preview (`queuedesk generate --scenario SC02 --seed 42 --unique 20
--repeats 5 --max-attempts 25 --dry-run`, mockups 09/10) does not exist. The real
interface is `scripts/generate` → `tools/Generate.java:47-58`:

```text
scripts/generate [--seed 42] [--count 20]
  [--scenario normal|burst|duplicate|conflict|invalid|reject|uncertain|unknown]
  [--run-id demo-42] [--send] [--observe]
  [--base-url http://127.0.0.1:8080] [--rate 2] [--concurrency 1]
  [--send-seconds 60] [--observe-seconds 90] [--report reports/<run>.json]
```

Dry run is the **default** (absence of `--send`); there is no `--dry-run` flag
(`Generate.java:88`). Bounds: count 1..500, rate 1..10, concurrency 1..4
(`Generate.java:68-70`). SC→CLI mapping (from `scenarios.py:108-190`):

| Catalog | Real preview (dry run; append `--send --observe` to execute) |
|---------|--------------------------------------------------------------|
| SC01 | `scripts/generate --scenario normal --seed 42 --count 20 --run-id <run>` |
| SC02 | `scripts/generate --scenario duplicate --seed 42 --count 20 --run-id <run>` (repeats = count/4, `Generate.java:192-198`) |
| SC03 | `scripts/generate --scenario conflict --seed 42 --count 20 --run-id <run>` |
| SC04 | `scripts/generate --scenario invalid --seed 42 --count 20 --run-id <run>` |
| SC05 | `scripts/generate --scenario burst --seed 42 --count 100 --rate 10 --concurrency 4 --run-id <run>` |
| SC06 | SC01 command + `scripts/mq down/up` around it (`scenarios.py:193-210`) |
| SC07 | SC01 count 1 + `scripts/redeliver <base> <iid> <eid>` (`scenarios.py:219`) |
| SC08 | SC01 count 1 + fault-latch restart (`scenarios.py:231-251`) |
| SC09a | `scripts/generate --scenario uncertain --seed 42 --count 20 --run-id <run>` |
| SC09b | `scripts/generate --scenario unknown --seed 42 --count 5 --observe-seconds 25 --run-id <run>` |
| SC10 | `scripts/poison <run> '<payload>'` (`scenarios.py:254`) |
| SC11 | `scripts/generate --scenario reject --seed 42 --count 20 --run-id <run>` |
| SC12 | SC01 + `--send-seconds 3` (`scenarios.py:184-190`) |
| RS01–RS06 | `scripts/risk-eval.py rs<N>` (new harness, §7) — never the generator alone |

Every preview endpoint returns this as a pre-rendered `commandPreview` string plus a
structured `commandArgs[]` so the UI copy button never re-derives flags.

## 9. Exact UI-endpoint contracts (all GET, read-only)

Conventions reused: `/api/v1` base (PRD §7, `InstructionController.java:15`);
`X-Next-Cursor` pagination (`InstructionController.java:57-59`); `ApiErrors`
`{"code","message"}` errors (`ApiErrors.java:21-54`): 400 `INVALID_REQUEST` /
`INVALID_CURSOR`, 404 `NOT_FOUND`, 503 `UNAVAILABLE`; unknown runs report zeros, never
404 (`RunController.java:13`); gauges return -1/unknown rather than fake zeros
(`QueuedeskMetrics.java:16-18`); dashboard polls 5s and pauses when hidden (PRD §15,
mockup headers). New controllers live in `dev.queuedesk.http` (read-only) and read
via `JdbcClient`/repositories; no new POST surface (`api.service.ts:9-11`).

### 9.1 Queues page — DASH-01 (mockup 06)

```text
GET /api/v1/ops/queues → 200
{
  "asOf": "2026-10-04T10:15:00Z",
  "outbox": { "pending": 6, "oldestPendingAgeSeconds": 250, "lastPublishedAt": "…",
              "dispatch": "BOUNDED", "lastEventId": "…" },
  "mq": { "connected": true, "queueManager": "QDESK",
          "depthEstimate": 12, "depthEstimateNote": "APPLICATION_ESTIMATE",
          "oldestEstimateAgeSeconds": 4.2,
          "activeConsumers": 1, "consumerState": "LISTENING",
          "lastSendAt": "…" },
  "quarantine": { "count": null, "lastObserved": 0, "lastObservedAt": "…",
                  "freshnessAgeSeconds": 150, "stale": true },
  "dedupLast15Min": 5
}
```

- `mq.depthEstimate` reuses the existing application-estimate queries
  (`QueuedeskMetrics.java:108-121`) — the backend has no PCF depth probe, and per
  PRD §17A must label it an estimate (:493). `activeConsumers` is 0/1 from the
  listener-container running state (`WorkerListener.LISTENER_ID`,
  `WorkerListener.java:30`), never a broker query. `quarantine` mirrors
  `QuarantineController` stale semantics (heartbeat older than 60s →
  `stale:true`, `QuarantineController.java:24-25`); when stale, `count` is `null`
  with `lastObserved` retained — never zero (`QueuedeskMetrics.java:16-18`).
- Errors: 503 `UNAVAILABLE` when the DB is unreachable (per-`ApiErrors.java:48-54`).
  Time-window param: `?windowMinutes=15` (default 15, 1..60; invalid → 400).

### 9.2 Telemetry page — DASH-02 (mockup 07)

```text
GET /api/v1/telemetry/summary?windowMinutes=15 → 200
{
  "asOf": "…", "windowMinutes": 15,
  "admission":   { "p95Ms": 22,  "n": 1240, "note": "ends at HTTP 202" },
  "completion":  { "p95Ms": 420, "n": 1208, "note": "ends at durable outcome commit; unresolved excluded" },
  "riskProvider":{ "p95Ms": 95,  "n": 1226, "mode": "RULES_PLUS_MOCK" },
  "sampling":    { "percent": 10, "sampledInstructions": 124 },
  "exporter":    { "status": "HEALTHY", "lastExportAt": "…", "spansSent15Min": 3684,
                   "spansDropped": 0, "bufferedSpans": 0 }
}
GET /api/v1/telemetry/latency-series?metric=admission|completion|riskProvider&windowMinutes=15 → 200
{ "metric": "admission", "bucketSeconds": 60,
  "points": [ { "at": "…", "p95Ms": 21, "n": 82 }, … ] }   // at most 60 buckets
GET /api/v1/telemetry/traces?limit=20 → 200
{ "items": [ { "instructionId": "…", "clientReference": "SYN-1048", "traceId": "…",
               "outcome": "COMPLETED_SIMULATED", "elapsedMs": 386 } ], "nextCursor": "…" }
```

- Percentiles come from Micrometer `Timer` percentiles (pattern
  `application.properties:52-53`) over the stated window; `n` is the timer count —
  never average per-instance percentiles (:493). `riskProvider` is `null`-valued
  with `"mode":"OFF"` when the gate is off. Series require a small bounded
  in-memory per-minute bucket ring (new component, ≤60 buckets × 3 metrics);
  without history the UI shows only the current window — no fabricated backfill
  (Micrometer retains no history server-side).
- `traces` reads `SpanRingBuffer` (`SpanRingBuffer.java:18-42`, capacity 1000,
  evictions counted) joined to instruction state; bounded IDs + timings only, no
  raw payloads (mockup 07 footer; PRD §17, :448). `exporter` maps
  `TelemetryCounters` (`created/exporterReceived/exportOk/exportFailed/
  bufferDropped`).

### 9.3 Trace detail — DASH-03 (mockup 08)

```text
GET /api/v1/telemetry/traces/{traceId} → 200
{
  "traceId": "…", "instructionId": "…", "clientReference": "SYN-1048",
  "participants": "SENDER_A → RECEIVER_B", "sampledPercent": 10,
  "businessOutcome": "COMPLETED_SIMULATED", "riskDecision": "ALLOW",
  "riskMode": "RULES_PLUS_MOCK", "endToEndMs": 364, "admissionMs": 22,
  "spans": [ { "spanId": "…", "parentSpanId": "…|null", "name": "queuedesk.worker.attempt",
               "startOffsetMs": 75, "durationMs": 215,
               "attributes": { "messaging.event.id": "…", "messaging.delivery.attempt": "1" } } ],
  "asyncLinks": [ { "fromSpanId": "…", "toSpanId": "…", "via": "msg-1048-01" } ],
  "auditUrl": "/api/v1/instructions/{id}"
}
```

- Spans are filtered from `SpanRingBuffer.spans()` by traceId; names are the real
  `queuedesk.*` names (`queuedesk.worker.attempt`, `queuedesk.outbox.publish`,
  `queuedesk.receiver.call`, `queuedesk.reconcile.settle`, + new
  `queuedesk.risk.evaluate`) — not the mockup's display labels. Offsets are
  relative to request start; waits are observed gaps, durations not additive
  (mockup 08 caption). Unknown/evicted traceId → 404 `NOT_FOUND` ("sampled
  diagnostics are not a durable audit trail" — ring eviction is honest loss).
- `riskDecision` is `null` when mode is OFF. `auditUrl` links to the durable
  instruction read ("View audit trail", mockup 08).

### 9.4 Scenarios + run detail — DASH-04 (mockups 09/10)

```text
GET /api/v1/scenarios → 200
{ "items": [
  { "id": "SC02", "title": "Duplicates", "group": "CORE",
    "blurb": "20 unique + 5 repeats", "seed": 42, "unique": 20, "repeats": 5,
    "maxAttempts": 25, "riskMode": "OFF",
    "commandPreview": "scripts/generate --scenario duplicate --seed 42 --count 20 --run-id <run>",
    "commandArgs": ["scripts/generate","--scenario","duplicate","--seed","42","--count","20","--run-id","<run>"],
    "dryRunDefault": true } ] }     // SC01–SC12 + RS01–RS06; RS items point at scripts/risk-eval.py
GET /api/v1/scenarios/runs?limit=20 → 200
{ "items": [ { "runId": "demo-0042", "scenarioId": "SC02|null", "scenarioTitle": "…",
               "startedAt": "…", "admitted": 20, "summary": "20 admitted · 5 repeat responses" } ],
  "nextCursor": "…" }
GET /api/v1/scenarios/runs/{runId} → 200
{
  "runId": "demo-0042", "scenarioId": "SC02", "scenarioTitle": "Duplicates",
  "startedAt": "…", "seed": null, "riskMode": "OFF",
  "httpAttempts": 25, "uniqueAdmitted": 20, "simulatedCompleted": 20,
  "unknownConfirmations": 0, "repeatResponses": 5, "duplicateOutcomes": 0,
  "assertions": [ { "text": "20 unique instructions admitted", "passed": true } ],
  "generator": { "scenario": "duplicate", "seed": null, "unique": 20, "repeats": 5,
                 "maxAttempts": 25, "participants": "SENDER_A → RECEIVER_B" },
  "commandPreview": "scripts/generate --scenario duplicate --seed <seed> --count 20 --run-id demo-0042 --send --observe",
  "commandArgs": [ … ], "dryRunDefault": true
}
```

- The catalog is static backend metadata (IDs/titles from PRD §14,
  `QueueDesk_PRD.md:367-381`, + RS01–RS06 from §25, :698-706) with REAL previews
  from §8. The UI executes nothing ("No scenario execution in this UI", mockup 09).
- Runs are discovered from durable state (`DISTINCT run_id` over `instruction`),
  not from `reports/*.json` (CLI-side files the server cannot rely on). Counts
  extend `RunSummary` (`RunController.java:17-18`); repeats/dedup come from
  `processing_attempt` (`DUPLICATE_SKIPPED`) and admission 200s. `seed` is `null`
  unless the runId convention carries it — the server never invents generator
  config; the preview uses `<seed>` placeholders for re-runs. Unknown runId →
  zeros, never 404 (`RunController.java:13`).
- `Export report` re-serves this JSON (same shape + `exportedAt`); there is no
  server-side PDF/CSV in this build.

### 9.5 Risk UI — DASH-05 (mockups 04/05)

```text
GET /api/v1/risk/summary?windowMinutes=15 → 200
{ "asOf": "…", "mode": "RULES_PLUS_MOCK", "riskHeld": 18,
  "reviewRequired": 14, "notAssessed": 4,
  "note": "Assessment output explains the hold. It is not a fraud determination." }
GET /api/v1/risk/assessments?assessment=REVIEW|NOT_ASSESSED&reason=NEW_RECIPIENT
    &q=SYN-1047&limit=20 → 200
{ "items": [ { "instructionId": "…", "clientReference": "SYN-1047",
               "amountMinor": 850000, "currency": "USD",
               "businessOutcome": "RISK_HELD", "riskOutput": "REVIEW",
               "reasonCodes": ["NEW_RECIPIENT","UNUSUAL_AMOUNT"] } ], "nextCursor": "…" }
GET /api/v1/risk/assessments/{instructionId} → 200
{
  "instructionId": "…", "clientReference": "SYN-1047",
  "participants": "SENDER_A → RECEIVER_B", "amountMinor": 850000, "currency": "USD",
  "businessOutcome": "RISK_HELD", "riskOutput": "REVIEW", "riskOutputText": "Review required",
  "features": { "amountMinor": 850000, "receiverSeenBefore": false,
                "senderMedianMinor": 95000, "asOf": "…", "missing": [] },
  "matchedRules": [ { "code": "NEW_RECIPIENT", "detail": "recipient_seen_before = false" } ],
  "policy": "Any matched rule → REVIEW",
  "signals": [ { "questionId": "Q01", "question": "Is the recipient new to this sender?",
                 "type": "boolean", "answer": true, "confidence": 0.96,
                 "confidenceNote": "Confidence is in the answer to the exact question. It is never the probability of fraud." } ],
  "timeline": [ { "stage": "features", "durationMs": 12 }, { "stage": "rules", "durationMs": 4 },
                { "stage": "mock_provider", "durationMs": 72 }, { "stage": "policy", "durationMs": 7 } ],
  "totalMs": 95, "assessedAt": "…", "traceId": "…",
  "versions": { "featureSchema": "1.0", "rules": "0.4", "policy": "0.4", "questions": "mock-2026-10" }
}
```

- Amounts stay integer minor units end-to-end (`amountMinor`, cf. `models.ts`
  `amountMinor: number` + minor-currency pipe); the UI formats. Unknown instruction
  or no assessment → 404 `NOT_FOUND`. Filters validate against the CHECK sets in
  V9; unknown values → 400 `INVALID_REQUEST`.

### 9.6 Instruction reads — DASH-06 (PRD §7, :205)

`InstructionController.View` (`InstructionController.java:20-28`) gains three
nullable fields; `models.ts InstructionView` mirrors them:

```json
{ …existing…, "riskStatus": "REVIEW|NOT_ASSESSED|ALLOW|REJECT|null",
  "assessmentId": "…|null", "riskReasonCodes": ["NEW_RECIPIENT"] }
```

`riskStatus=null` when mode is OFF or no assessment exists. List filtering adds
`status=RISK_HELD` (extends `KNOWN_STATES`, `AdmissionService.java:29-30`).

## 10. Build order + acceptance

1. V9 migration + `RISK_HELD` transitions + `BusinessState` (all existing tests green;
   OFF mode is behavior-identical — "base application keeps working", :642).
2. Feature snapshot + rules + mock provider + policy (RISK-01–03); provider-swap
   tests without policy changes (PR13).
3. Split-tx gate wiring (RISK-04); fault tests: timeout/invalid/unavailable →
   NOT_ASSESSED hold, receiver `calls`=0 (PR14); redelivery → cached assessment
   (PR15).
4. Read endpoints DASH-01–06 + dashboard pages; quote every mockup number as live or
   explicitly unavailable (never zero-masked).
5. `scripts/risk-eval.py` + oracle + RS01–RS06 reports (RISK-05, RISK-06/TG04).
6. No live call by default; config rejects `RULES_PLUS_JEV` (PR16).

Acceptance: PR13–PR16 + RS01–RS06 pass locally with reproducible reports; TG04
separates ops latency from offline accuracy; provider failure holds safely and never
silently changes modes; no replacement instruction is ever created
(`BACKLOG.md:66`).

# Requirements: QueueDesk

**Defined:** 2026-10-04
**Core Value:** Trace a synthetic instruction to a truthful durable simulated outcome and explain recovery without duplicating business intent.
**Source:** [QueueDesk PRD](../docs/QueueDesk_PRD.md). Original IDs remain intact; additional IDs expose detailed acceptance already specified there.

## Roadmap Requirements

Phases 1–4 deliver L1/L2, Phases 5–7 deliver the reliability gate, and Phase 8 is the optional L4 dashboard chapter included in the full-feature plan. Pending means final phase acceptance remains open, even where the starter already implements part of the behavior.

### Foundation and durable admission

- [x] **SK01**: Plain Java instruction types validate synthetic participants, USD, and integer minor-unit amounts without Spring; align the target cap to 1..10,000,000. (PRD §3, §11; Phase 1.)
- [x] **SK02**: POST returns 202 only after the admission database transaction commits; a failed commit never produces successful admission. (PRD §3, §7; Phase 1.)
- [x] **SK03**: GET by instruction ID returns the saved instruction and truthful current state after an API restart; unknown IDs return 404. (PRD §3, §7; Phase 1.)
- [x] **SK04**: The Java generator defaults to dry run and loopback; the same seed/options reproduce the same business payloads. (PRD §3; Phase 1.)
- [x] **SK05**: A new reader can start the API and Postgres, apply migrations, submit/read one instruction, run tests and shut down using the runbook. (PRD §3; Phase 1.)
- [x] **PR02**: Bad schema, amount or participant returns 400 before persistence and creates no instruction; after outbox introduction it also creates no event. (PRD §4, §7; Phase 1.)
- [x] **PR04**: Same normalized key/body returns 200 with the original ID and current state; changed semantic payload returns 409; concurrent submissions cannot create duplicates or extra admission events. (PRD §4, §7; Phase 1.)
- [x] **PR10**: API, dependency ports and generator defaults stay local, synthetic and bounded; credentials stay outside source/logs and no real payment endpoint is targeted. (PRD §4, §17; Phase 1.)

### HTTP and storage contracts

- [x] **API-01**: Enforce an actual 16 KiB request-body limit with 413, including streamed bodies; structured 400/409/413/503 errors expose stable safe codes and no connection strings. (PRD §7, §8; Phase 1.)
- [x] **API-02**: List instructions by runId and business status with bounded pagination, default limit 50 and a documented deterministic continuation/order contract. (PRD §7; Phase 1.)
- [x] **DATA-01**: Migrations create durable instruction identities, a unique idempotency key, semantic request hash excluding correlation metadata, BIGINT amount checks, UTC timestamps, version, run/state indexes and instruction_audit transition records; each admission transition commits its audit row in the same transaction. (PRD §7, §9; Phase 1.)
- [x] **ENV-01**: Record observed JDK/Boot/Maven/OS/CPU plus a verified supported Postgres/container combination, local resource budget, configuration examples and migration/restart procedure. (PRD §16, §17, §20; Phase 1.)

### Learning checkpoints

- [x] **LEARN-01**: The learner can explain records, validation, constructor injection, controller/service/repository boundaries, database constraints, transaction commit and restart survival using a small change and failing test. Non-blocking: verified once at the final project review; the phase closes when the exercise material is prepared. (PRD §18, §19, §21; Phase 1.)

### Outbox and MQ publishing

- [x] **PR03**: Admission atomically commits one instruction and one outbox event; rollback leaves neither, and broker outage leaves durable pending work. (PRD §4, §10; Phase 2.)
- [x] **MSG-01**: Persistent UTF-8 JSON on QUEUEDESK.INSTRUCTION.IN carries schemaVersion, eventType, stable eventId/instructionId, runId, occurredAt, correlationId and validated payload. (PRD §8; Phase 2.)
- [x] **OUT-01**: One publisher polls a bounded due batch, commits a local transacted JMS send, then marks PUBLISHED in a second DB transaction; uncertain sends reuse eventId. (PRD §10; Phase 2.)
- [x] **OUT-02**: Publisher failures retain pending work, persist distinct attempt/due/error metadata and use capped reconnect/backoff with jitter; backlog/capacity guards never drop stored work. (PRD §9, §10, §12; Phase 2.)
- [x] **ENV-02**: Before MQ runs, document exact server/client/Jakarta integration compatibility, image/build CPU support and developer-use terms; configure bounded local resources and persistent storage. (PRD §16, §20; Phase 2.)

### Worker outcomes and deduplication

- [x] **PR05**: Stable event/instruction identities and a database dedup key yield one durable business outcome on repeated delivery, with visible duplicate evidence. (PRD §4, §11; Phase 3.)
- [x] **PR08**: Every admitted instruction is retrievable by ID and runId with business outcome, reason, delivery detail, identifiers and meaningful timestamps. (PRD §4, §6, §7; Phase 3.)
- [x] **WORK-01**: One consumer validates envelopes; processed_message, business updates and the transition audit row commit in one DB transaction before the local JMS delivery commits. (PRD §9, §11; Phase 3.)
- [x] **WORK-02**: Deterministic local ClearingHousePolicy and receiver implementations produce simulated success or explicit business rejection; rejection commits normally and is not retried as poison. (PRD §11; Phase 3.)
- [x] **STATE-01**: Enforce the documented business-state transitions, persisting BUSINESS_ACCEPTED as a visible intermediate state, and keep PENDING_PUBLISH/PUBLISHED/RETRYING/QUARANTINED separate; no terminal result regresses or depends on a published marker. (PRD §6; Phase 3.)
- [x] **DATA-02**: Retain instruction/outbox/dedup identities for the lab run; processing attempts are telemetry and failed-attempt evidence survives rollback via controlled writes or logs. (PRD §9; Phase 3.)
- [x] **LEARN-02**: The learner can trace DB and JMS commits, explain why @Transactional does not automatically include MQ, and show why JMSMessageID is not the business dedup key. Non-blocking: verified once at the final project review; the phase closes when the exercise material is prepared. (PRD §18, §19, §22; Phase 3.)

### Generator and core MVP evidence

- [x] **PR01**: The script remains the primary producer; a normal seed-42 run admits 20 distinct instructions and observes 20 COMPLETED_SIMULATED outcomes within 90 seconds. (PRD §4, §13, §14; Phase 4.)
- [x] **GN01**: Dry run prints/writes planned payloads and effective configuration without any network calls. (PRD §13; Phase 4.)
- [x] **GN02**: The same seed/options reproduce payloads and intended fixture faults, excluding wall-clock timestamps and server-generated identities. (PRD §13, §14; Phase 4.)
- [x] **GN03**: Only allowlisted loopback targets are accepted by default; redirects and nonlocal/real hosts are refused. (PRD §13; Phase 4.)
- [x] **GN04**: Defaults are 20 instructions, 2 requests/sec, concurrency 1, 5-second requests and a 60-second sending cap. (PRD §13; Phase 4.)
- [x] **GN05**: Timeout/503 recovery preserves the exact original key/body; validation and conflict errors are never retried automatically. (PRD §13; Phase 4.)
- [x] **GN06**: Enforce hard caps of 500 submissions, 10 requests/sec, concurrency 4 and five minutes; any cap increase is an explicit lab change. (PRD §13; Phase 4.)
- [x] **GN07**: Sending and observing are separate; record HTTP results then poll every two seconds with a separate 90-second completion deadline. (PRD §13; Phase 4.)
- [x] **GN08**: Reports retain pending/unknown results and separate HTTP success from business completion; exit 0 requires scenario assertions and unresolved work has a distinct nonzero exit. (PRD §13; Phase 4.)
- [x] **GEN-01**: Reports store seed, fixture version, configuration, runId, timestamps, attempted/accepted/repeated/invalid/unknown counts, outcome totals, separate HTTP/end-to-end latency distributions and unresolved work. (PRD §13, §14, §17; Phase 4.)
- [x] **GEN-02**: Scenario fixtures cover normal, 20 unique plus five duplicate attempts, changed-body conflict, invalid payloads, capped burst, business rejection and stop/deadline behavior. (PRD §14 SC01–SC05, SC11–SC12; Phase 4.)
- [x] **TEST-01**: Core end-to-end evidence accounts for unique instructions, admission events and outcomes for SC01–SC05 and SC11–SC12; compile/unit/HTTP and real-Postgres checks run as explicit gates. (PRD §16, §22; Phase 4.)

### Retry and quarantine

- [x] **PR06**: Only classified transient failures retry under a bounded policy; poison reaches quarantine with a stable reason and no endless hot loop. (PRD §4, §12; Phase 5.)
- [x] **RETRY-01**: Document and prove a maximum of three failed processing deliveries under the selected listener/provider semantics; pacing is outside business transactions and publisher/consumer counters stay separate. (PRD §12; Phase 5.)
- [x] **QUAR-01**: Malformed/unsupported envelopes and impossible invariants are held without false business rejection; one poison-routing owner configures BOTHRESH/BOQNAME, permissions and persistent-work preservation when routing fails. (PRD §8, §12; Phase 5.)
- [x] **QUAR-02**: A reconciled quarantine_record view identifies held events/reasons with freshness; DB projection failure never masquerades as zero quarantine or an atomic MQ/DB update. (PRD §9, §12; Phase 5.)

### Uncertain outcome reconciliation

- [x] **PR07**: An unknown receiver outcome remains pending; reconciliation looks up the original instruction ID and never silently creates a replacement instruction. (PRD §4, §11; Phase 6.)
- [x] **REC-01**: ReceiverGateway stores one durable result per instructionId in an independent transaction before optional response loss; repeat calls return that same result. (PRD §11; Phase 6.)
- [x] **REC-02**: Lost response atomically records PENDING_CONFIRMATION, processed_message and a unique reconciliation task; due/count/status persist and bounded lookup updates only the original instruction on definitive evidence. (PRD §9, §11; Phase 6.)
- [x] **REC-03**: Still-unknown lookups remain pending, definitive rejection/success follows legal transitions, and uncertain transient calls reconcile before any reissue. (PRD §6, §11, §12; Phase 6.)

### Recovery proof and operations

- [x] **PR09**: Outage/crash-window scenarios pass with retained data and repeatable evidence; restarting restores progress without deleting records or duplicating a definitive outcome. (PRD §4, §12, §16; Phase 7.)
- [x] **FAIL-01**: A local-only harness uses controlled hooks/latches for publisher-after-MQ-commit and worker-before/after-DB-commit crashes, MQ/DB outages, redelivery, lost replies and unavailable quarantine routes. (PRD §12, §14, §16; Phase 7.)
- [x] **FAIL-02**: SC06–SC10 assert both sides of failure boundaries with bounded eventual checks, stable IDs, one outcome and no silent loss; faults refuse non-lab execution and touch only named QueueDesk resources. (PRD §14, §16; Phase 7.)
- [x] **OBS-01**: Structured logs include UTC timestamp, component, stage, runId, instructionId, eventId, deliveryAttempt, correlationId, outcome and safe reason; include traceId/spanId when available; elapsed durations use monotonic timing. (PRD §17; Phase 7.)
- [x] **OBS-02**: Expose admission/rejection/completion totals, outbox count/oldest age, dedup/failure totals, pending-confirmation count/age, quarantine count/freshness, MQ depth/oldest age/active work, exporter failures/dropped telemetry and separate latency percentiles without ID metric labels. (PRD §17; Phase 7.)
- [x] **TEL-01**: Propagate trace context across admission, outbox and MQ (one instrumentation owner, no duplicate HTTP/JMS spans); span every attempt and link redeliveries to the original work; unsampled traces never imply loss. (PRD §17A TG02; Phase 7.)
- [x] **TEL-02**: Telemetry exporter/backend failure never blocks admission or acknowledgement; bounded buffers, timeouts and retries cap resource growth and diagnostic drops stay visible. (PRD §17A TG03; Phase 7.)
- [x] **OPS-01**: Required local health endpoints distinguish process liveness from dependency availability; MQ outages do not cause liveness restart loops or hide unavailable data. (PRD §17; Phase 7.)
- [x] **SAFE-01**: Bound pools, container memory/logs and queue alerts; stop new generated traffic above 1,000 backlog or below configured disk reserve (proposed 10 GB), preserving pending work and an explicit shutdown path. (PRD §17; Phase 7.)
- [x] **OPS-02**: A fresh-reader recovery runbook explains diagnosis, pending/quarantine evidence, restart/recovery, limits and scoped shutdown; CI labels real Postgres/MQ suites and never reports skipped integration tests as passed. (PRD §16, §17, §22; Phase 7.)
- [x] **LEARN-03**: The learner demonstrates normal, duplicate and outage runs, explains a publisher/worker crash and reconciles a lost response by the original identity, recording what changed and why. Non-blocking: verified once at the final project review; the phase closes when the exercise material is prepared. (PRD §19, §21, §22; Phase 7.)

### Read-only Angular

- [x] **PR11**: After backend reliability gates, Angular presents summary, filtered list, detail timeline and freshness using the API as the source of truth. (PRD §4, §15, §20; Phase 8.)
- [x] **UI-01**: Overview cards show business-status counts, pending-publish count/oldest age and unresolved confirmations with query scope and last refresh. (PRD §15; Phase 8.)
- [x] **UI-02**: The instruction list filters by runId, status and time with bounded pagination and synthetic reference, integer-formatted amount/currency, status and age. (PRD §15; Phase 8.)
- [x] **UI-03**: Instruction detail shows identifiers, immutable request, current result/reason and ordered timeline with transport evidence separate from business events; marker lag never overwrites completion. (PRD §15; Phase 8.)
- [x] **UI-04**: Run detail compares expected unique, admitted, completed, rejected, duplicate and unresolved counts from persisted run metadata/API evidence. (PRD §15; Phase 8.)
- [x] **UI-05**: Operational views show outbox backlog, MQ state and quarantine-projection freshness; five-second polling pauses when hidden and handles loading, empty, unavailable and stale states. (PRD §15; Phase 8.)
- [x] **UI-06**: Keyboard-accessible controls, text/icon status labels, consistent integer currency formatting and a permanent simulated label remain usable without relying on color. (PRD §15, §16; Phase 8.)

### Local Kubernetes

- [x] **K8S-01**: Local Kubernetes runs the backend with bounded resources and working health checks; pod restarts preserve scenario behavior and persistent data; teardown affects only named lab resources. (PRD §20; Phase 9.)

## Deferred Requirements

These have a concrete scope and promotion gate in [BACKLOG.md](BACKLOG.md); they are not part of core MVP or automatically executable after Phase 8.

- **PR12**: Selected-item replay/manual resolution requires current-state/outcome re-check, original identity, explicit confirmation, reason and actor/time/old-new-state/correlation audit. Owner: B01.
- **PR13–PR16 + RS01–RS06 + TG04**: Optional L5 fraud-risk lab (mock-first provider/policy/hold behavior, held-out evaluation harness, read-only risk panel). Owner: B04; PRD §§15, 17A, 23–25.

### B04 detail IDs (no renumbering: the 64 roadmap requirements above are untouched)

Detail acceptance for the B04 deferred scope, per `.planning/phases/10-mockup-parity/B04-DESIGN.md`:

- [x] **RISK-01**: Versioned Java feature snapshots (amount, unique velocity, elapsed, receiver history, frozen as-of, explicit missingness); history scopes to the run and to strictly older rows, so retries/duplicates never inflate velocity. (PRD §24.)
- [x] **RISK-02**: RiskSignalProvider contract with Java-first validation and OFF/RULES_ONLY/RULES_PLUS_MOCK modes plus a deterministic mock; RULES_PLUS_JEV fails config validation and never silently degrades. (PRD §§23–24; PR13, PR16.)
- [x] **RISK-03**: Java policy composition (ALLOW/REVIEW/NOT_ASSESSED/REJECT) with predefined reason codes; holds commit atomically and the receiver is never called on hold. (PRD §24; PR14.)
- [x] **RISK-04**: Split-transaction gate (short plan tx, provider call outside any tx, atomic final tx), durable attempt lease with conflict-hold, assessment reuse under frozen versions, and audited resume via continuation on the original instructionId. (PRD §24; PR15.)
- [x] **RISK-05**: Evaluator-only held-out harness (`scripts/risk-eval.py` + `scripts/risk-oracle.json`) running RS01–RS06 with disjoint seeds and reproducible reports. (PRD §25.)
- [x] **RISK-06**: Risk ops meters (holds by result, provider calls by status, provider latency) kept separate from offline accuracy (precision/recall from labeled reports only). (PRD §§17, 17A; TG04.)
- [x] **DASH-01**: Queues page endpoint (outbox/MQ-estimate/quarantine freshness; estimates labeled, stale never zero-masked). (PRD §15.)
- [x] **DASH-02**: Telemetry page endpoints (latency cards/series from observed buckets, sampled traces, exporter health). (PRD §17A.)
- [x] **DASH-03**: Trace-detail endpoint (waterfall spans by traceId; evicted/unknown 404 honestly). (PRD §17A.)
- [x] **DASH-04**: Scenario catalog with real CLI previews plus run evidence from durable state (unknown runs report zeros). (PRD §§14–15.)
- [x] **DASH-05**: Read-only risk endpoints (summary, held list, assessment detail); review actions stay deferred (POST 404 until B01). (PRD §15.)
- [x] **DASH-06**: Instruction reads extended with riskStatus/assessmentId/riskReasonCodes; list filters accept status=RISK_HELD. (PRD §7.)

## Out of Scope

| Feature | Reason |
|---------|--------|
| Real financial endpoints, money movement, account/customer data | Synthetic teaching model only |
| Ledger, settlement finality, actual scheme/ISO 20022 or regulatory claims | Excluded by PRD §1 |
| Production SaaS, multi-tenancy, HA and public exposure in MVP | Local-first learning and resource constraints |
| Manual payment entry replacing CLI | Generator is the required primary producer |
| Bulk automatic replay of pending outcomes | Unknown outcomes require identity-preserving reconciliation |
| Automatic rejection merely because a timeout elapsed | Absence of a reply is not definitive business evidence |
| AWS/Terraform deployment, public exposure | User decision 2026-10-04: local-only lab, no deployment plans |

## Traceability

Every roadmap requirement has exactly one owning phase. Subsequent phases re-test earlier contracts without changing ownership.

| Requirement | Phase | Status |
|-------------|-------|--------|
| SK01 | Phase 1 | Done |
| SK02 | Phase 1 | Done |
| SK03 | Phase 1 | Done |
| SK04 | Phase 1 | Done |
| SK05 | Phase 1 | Done |
| PR02 | Phase 1 | Done |
| PR04 | Phase 1 | Done |
| PR10 | Phase 1 | Done |
| API-01 | Phase 1 | Done |
| API-02 | Phase 1 | Done |
| DATA-01 | Phase 1 | Done |
| ENV-01 | Phase 1 | Done |
| LEARN-01 | Phase 1 | Pending |
| PR03 | Phase 2 | Done |
| MSG-01 | Phase 2 | Done |
| OUT-01 | Phase 2 | Done |
| OUT-02 | Phase 2 | Done |
| ENV-02 | Phase 2 | Done |
| PR05 | Phase 3 | Done |
| PR08 | Phase 3 | Done |
| WORK-01 | Phase 3 | Done |
| WORK-02 | Phase 3 | Done |
| STATE-01 | Phase 3 | Done |
| DATA-02 | Phase 3 | Done |
| LEARN-02 | Phase 3 | Pending |
| PR01 | Phase 4 | Done |
| GN01 | Phase 4 | Done |
| GN02 | Phase 4 | Done |
| GN03 | Phase 4 | Done |
| GN04 | Phase 4 | Done |
| GN05 | Phase 4 | Done |
| GN06 | Phase 4 | Done |
| GN07 | Phase 4 | Done |
| GN08 | Phase 4 | Done |
| GEN-01 | Phase 4 | Done |
| GEN-02 | Phase 4 | Done |
| TEST-01 | Phase 4 | Done |
| PR06 | Phase 5 | Done |
| RETRY-01 | Phase 5 | Done |
| QUAR-01 | Phase 5 | Done |
| QUAR-02 | Phase 5 | Done |
| PR07 | Phase 6 | Done |
| REC-01 | Phase 6 | Done |
| REC-02 | Phase 6 | Done |
| REC-03 | Phase 6 | Done |
| PR09 | Phase 7 | Done |
| FAIL-01 | Phase 7 | Done |
| FAIL-02 | Phase 7 | Done |
| OBS-01 | Phase 7 | Done |
| OBS-02 | Phase 7 | Done |
| TEL-01 | Phase 7 | Done |
| TEL-02 | Phase 7 | Done |
| OPS-01 | Phase 7 | Done |
| SAFE-01 | Phase 7 | Done |
| OPS-02 | Phase 7 | Done |
| LEARN-03 | Phase 7 | Done |
| PR11 | Phase 8 | Pending |
| UI-01 | Phase 8 | Pending |
| UI-02 | Phase 8 | Pending |
| UI-03 | Phase 8 | Pending |
| UI-04 | Phase 8 | Pending |
| UI-05 | Phase 8 | Pending |
| UI-06 | Phase 8 | Pending |
| K8S-01 | Phase 9 | Pending |

**Coverage:**
- Roadmap requirements: 64
- Mapped to exactly one phase: 64
- Unmapped: 0
- Original source IDs: 29 total; 24 in the roadmap, PR12 in B01 and PR13–PR16 in B04
- Additional roadmap detail IDs: 40
- Scenario coverage: SC01–SC12 and RS01–RS06 in VALIDATION.md

## Baseline Notes

SK01/SK03/SK04, PR02/PR04/PR10 and GN01–GN06 have partial or existing starter behavior. The amount cap, database semantics, full configurable CLI limits and downstream integration still require acceptance. See [BASELINE.md](codebase/BASELINE.md); no distributed-system gate is marked complete.

---
*Last updated: 2026-10-04 after source-to-phase coverage review.*

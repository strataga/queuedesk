# Requirements: QueueDesk

**Defined:** 2026-10-04
**Core Value:** Trace a synthetic instruction to a truthful durable simulated outcome and explain recovery without duplicating business intent.
**Source:** [QueueDesk PRD](../docs/QueueDesk_PRD.md). Original IDs remain intact; additional IDs expose detailed acceptance already specified there.

## Roadmap Requirements

Phases 1–4 deliver L1/L2, Phases 5–7 deliver the reliability gate, and Phase 8 is the optional L4 dashboard chapter included in the full-feature plan. Pending means final phase acceptance remains open, even where the starter already implements part of the behavior.

### Foundation and durable admission

- [ ] **SK01**: Plain Java instruction types validate synthetic participants, USD, and integer minor-unit amounts without Spring; align the target cap to 1..10,000,000. (PRD §3, §11; Phase 1.)
- [ ] **SK02**: POST returns 202 only after the admission database transaction commits; a failed commit never produces successful admission. (PRD §3, §7; Phase 1.)
- [ ] **SK03**: GET by instruction ID returns the saved instruction and truthful current state after an API restart; unknown IDs return 404. (PRD §3, §7; Phase 1.)
- [ ] **SK04**: The Java generator defaults to dry run and loopback; the same seed/options reproduce the same business payloads. (PRD §3; Phase 1.)
- [ ] **SK05**: A new reader can start the API and Postgres, apply migrations, submit/read one instruction, run tests and shut down using the runbook. (PRD §3; Phase 1.)
- [ ] **PR02**: Bad schema, amount or participant returns 400 before persistence and creates no instruction; after outbox introduction it also creates no event. (PRD §4, §7; Phase 1.)
- [ ] **PR04**: Same normalized key/body returns 200 with the original ID and current state; changed semantic payload returns 409; concurrent submissions cannot create duplicates or extra admission events. (PRD §4, §7; Phase 1.)
- [ ] **PR10**: API, dependency ports and generator defaults stay local, synthetic and bounded; credentials stay outside source/logs and no real payment endpoint is targeted. (PRD §4, §17; Phase 1.)

### HTTP and storage contracts

- [ ] **API-01**: Enforce an actual 16 KiB request-body limit with 413, including streamed bodies; structured 400/409/413/503 errors expose stable safe codes and no connection strings. (PRD §7, §8; Phase 1.)
- [ ] **API-02**: List instructions by runId and business status with bounded pagination, default limit 50 and a documented deterministic continuation/order contract. (PRD §7; Phase 1.)
- [ ] **DATA-01**: Migrations create durable instruction identities, a unique idempotency key, semantic request hash excluding correlation metadata, BIGINT amount checks, UTC timestamps, version and run/state indexes. (PRD §7, §9; Phase 1.)
- [ ] **ENV-01**: Record observed JDK/Boot/Maven/OS/CPU plus a verified supported Postgres/container combination, local resource budget, configuration examples and migration/restart procedure. (PRD §16, §17, §20; Phase 1.)

### Learning checkpoints

- [ ] **LEARN-01**: The learner can explain records, validation, constructor injection, controller/service/repository boundaries, database constraints, transaction commit and restart survival using a small change and failing test. (PRD §18, §19, §21; Phase 1.)

### Outbox and MQ publishing

- [ ] **PR03**: Admission atomically commits one instruction and one outbox event; rollback leaves neither, and broker outage leaves durable pending work. (PRD §4, §10; Phase 2.)
- [ ] **MSG-01**: Persistent UTF-8 JSON on QUEUEDESK.INSTRUCTION.IN carries schemaVersion, eventType, stable eventId/instructionId, runId, occurredAt, correlationId and validated payload. (PRD §8; Phase 2.)
- [ ] **OUT-01**: One publisher polls a bounded due batch, commits a local transacted JMS send, then marks PUBLISHED in a separate DB transaction; uncertain sends reuse eventId. (PRD §10; Phase 2.)
- [ ] **OUT-02**: Publisher failures retain pending work, persist distinct attempt/due/error metadata and use capped reconnect/backoff with jitter; backlog/capacity guards never drop stored work. (PRD §9, §10, §12; Phase 2.)
- [ ] **ENV-02**: Before MQ runs, document exact server/client/Jakarta integration compatibility, image/build CPU support and developer-use terms; configure bounded local resources and persistent storage. (PRD §16, §20; Phase 2.)

### Worker outcomes and deduplication

- [ ] **PR05**: Stable event/instruction identities and a database dedup key yield one durable business outcome on repeated delivery, with visible duplicate evidence. (PRD §4, §11; Phase 3.)
- [ ] **PR08**: Every admitted instruction is retrievable by ID and runId with business outcome, reason, delivery detail, identifiers and meaningful timestamps. (PRD §4, §6, §7; Phase 3.)
- [ ] **WORK-01**: One consumer validates envelopes; processed_message and business updates commit in one DB transaction before the local JMS delivery commits. (PRD §9, §11; Phase 3.)
- [ ] **WORK-02**: Deterministic local ClearingHousePolicy and receiver implementations produce simulated success or explicit business rejection; rejection commits normally and is not retried as poison. (PRD §11; Phase 3.)
- [ ] **STATE-01**: Enforce the documented business-state transitions and keep PENDING_PUBLISH/PUBLISHED/RETRYING/QUARANTINED separate; no terminal result regresses or depends on a published marker. (PRD §6; Phase 3.)
- [ ] **DATA-02**: Retain instruction/outbox/dedup identities for the lab run; processing attempts are telemetry and failed-attempt evidence survives rollback via controlled writes or logs. (PRD §9; Phase 3.)
- [ ] **LEARN-02**: The learner can trace DB and JMS commits, explain why @Transactional does not automatically include MQ, and show why JMSMessageID is not the business dedup key. (PRD §18, §19, §22; Phase 3.)

### Generator and core MVP evidence

- [ ] **PR01**: The script remains the primary producer; a normal seed-42 run admits 20 distinct instructions and observes 20 COMPLETED_SIMULATED outcomes within 90 seconds. (PRD §4, §13, §14; Phase 4.)
- [ ] **GN01**: Dry run prints/writes planned payloads and effective configuration without any network calls. (PRD §13; Phase 4.)
- [ ] **GN02**: The same seed/options reproduce payloads and intended fixture faults, excluding wall-clock timestamps and server-generated identities. (PRD §13, §14; Phase 4.)
- [ ] **GN03**: Only allowlisted loopback targets are accepted by default; redirects and nonlocal/real hosts are refused. (PRD §13; Phase 4.)
- [ ] **GN04**: Defaults are 20 instructions, 2 requests/sec, concurrency 1, 5-second requests and a 60-second sending cap. (PRD §13; Phase 4.)
- [ ] **GN05**: Timeout/503 recovery preserves the exact original key/body; validation and conflict errors are never retried automatically. (PRD §13; Phase 4.)
- [ ] **GN06**: Enforce hard caps of 500 submissions, 10 requests/sec, concurrency 4 and five minutes; any cap increase is an explicit lab change. (PRD §13; Phase 4.)
- [ ] **GN07**: Sending and observing are separate; record HTTP results then poll every two seconds with a separate 90-second completion deadline. (PRD §13; Phase 4.)
- [ ] **GN08**: Reports retain pending/unknown results and separate HTTP success from business completion; exit 0 requires scenario assertions and unresolved work has a distinct nonzero exit. (PRD §13; Phase 4.)
- [ ] **GEN-01**: Reports store seed, fixture version, configuration, runId, timestamps, attempted/accepted/repeated/invalid/unknown counts, outcome totals, separate HTTP/end-to-end latency distributions and unresolved work. (PRD §13, §14, §17; Phase 4.)
- [ ] **GEN-02**: Scenario fixtures cover normal, 20 unique plus five duplicate attempts, changed-body conflict, invalid payloads, capped burst, business rejection and stop/deadline behavior. (PRD §14 SC01–SC05, SC11–SC12; Phase 4.)
- [ ] **TEST-01**: Core end-to-end evidence accounts for unique instructions, admission events and outcomes for SC01–SC05 and SC11–SC12; compile/unit/HTTP and real-Postgres checks run as explicit gates. (PRD §16, §22; Phase 4.)

### Retry and quarantine

- [ ] **PR06**: Only classified transient failures retry under a bounded policy; poison reaches quarantine with a stable reason and no endless hot loop. (PRD §4, §12; Phase 5.)
- [ ] **RETRY-01**: Document and prove a maximum of three failed processing deliveries under the selected listener/provider semantics; pacing is outside business transactions and publisher/consumer counters stay separate. (PRD §12; Phase 5.)
- [ ] **QUAR-01**: Malformed/unsupported envelopes and impossible invariants are held without false business rejection; one poison-routing owner configures BOTHRESH/BOQNAME, permissions and persistent-work preservation when routing fails. (PRD §8, §12; Phase 5.)
- [ ] **QUAR-02**: A reconciled quarantine_record view identifies held events/reasons with freshness; DB projection failure never masquerades as zero quarantine or an atomic MQ/DB update. (PRD §9, §12; Phase 5.)

### Uncertain outcome reconciliation

- [ ] **PR07**: An unknown receiver outcome remains pending; reconciliation looks up the original instruction ID and never silently creates a replacement instruction. (PRD §4, §11; Phase 6.)
- [ ] **REC-01**: ReceiverGateway stores one durable result per instructionId in an independent transaction before optional response loss; repeat calls return that same result. (PRD §11; Phase 6.)
- [ ] **REC-02**: Lost response atomically records PENDING_CONFIRMATION, processed_message and a unique reconciliation task; due/count/status persist and bounded lookup updates only the original instruction on definitive evidence. (PRD §9, §11; Phase 6.)
- [ ] **REC-03**: Still-unknown lookups remain pending, definitive rejection/success follows legal transitions, and uncertain transient calls reconcile before any reissue. (PRD §6, §11, §12; Phase 6.)

### Recovery proof and operations

- [ ] **PR09**: Outage/crash-window scenarios pass with retained data and repeatable evidence; restarting restores progress without deleting records or duplicating a definitive outcome. (PRD §4, §12, §16; Phase 7.)
- [ ] **FAIL-01**: A local-only harness uses controlled hooks/latches for publisher-after-MQ-commit and worker-before/after-DB-commit crashes, MQ/DB outages, redelivery, lost replies and unavailable quarantine routes. (PRD §12, §14, §16; Phase 7.)
- [ ] **FAIL-02**: SC06–SC10 assert both sides of failure boundaries with bounded eventual checks, stable IDs, one outcome and no silent loss; faults refuse non-lab execution and touch only named QueueDesk resources. (PRD §14, §16; Phase 7.)
- [ ] **OBS-01**: Structured logs include UTC timestamp, component, runId, instructionId, eventId, correlationId, outcome and safe reason; elapsed durations use monotonic timing. (PRD §17; Phase 7.)
- [ ] **OBS-02**: Expose admission/rejection/completion totals, outbox count/oldest age, dedup/failure totals, pending-confirmation count/age, quarantine count/freshness and separate latency percentiles without ID metric labels. (PRD §17; Phase 7.)
- [ ] **OPS-01**: Required local health endpoints distinguish process liveness from dependency availability; MQ outages do not cause liveness restart loops or hide unavailable data. (PRD §17; Phase 7.)
- [ ] **SAFE-01**: Bound pools, container memory/logs and queue alerts; stop new generated traffic above 1,000 backlog or below configured disk reserve (proposed 10 GB), preserving pending work and an explicit shutdown path. (PRD §17; Phase 7.)
- [ ] **OPS-02**: A fresh-reader recovery runbook explains diagnosis, pending/quarantine evidence, restart/recovery, limits and scoped shutdown; CI labels real Postgres/MQ suites and never reports skipped integration tests as passed. (PRD §16, §17, §22; Phase 7.)
- [ ] **LEARN-03**: The learner demonstrates normal, duplicate and outage runs, explains a publisher/worker crash and reconciles a lost response by the original identity, recording what changed and why. (PRD §19, §21, §22; Phase 7.)

### Read-only Angular

- [ ] **PR11**: After backend reliability gates, Angular presents summary, filtered list, detail timeline and freshness using the API as the source of truth. (PRD §4, §15, §20; Phase 8.)
- [ ] **UI-01**: Overview cards show business-status counts, pending-publish count/oldest age and unresolved confirmations with query scope and last refresh. (PRD §15; Phase 8.)
- [ ] **UI-02**: The instruction list filters by runId, status and time with bounded pagination and synthetic reference, integer-formatted amount/currency, status and age. (PRD §15; Phase 8.)
- [ ] **UI-03**: Instruction detail shows identifiers, immutable request, current result/reason and ordered timeline with transport evidence separate from business events; marker lag never overwrites completion. (PRD §15; Phase 8.)
- [ ] **UI-04**: Run detail compares expected unique, admitted, completed, rejected, duplicate and unresolved counts from persisted run metadata/API evidence. (PRD §15; Phase 8.)
- [ ] **UI-05**: Operational views show outbox backlog, MQ state and quarantine-projection freshness; five-second polling pauses when hidden and handles loading, empty, unavailable and stale states. (PRD §15; Phase 8.)
- [ ] **UI-06**: Keyboard-accessible controls, text/icon status labels, consistent integer currency formatting and a permanent simulated label remain usable without relying on color. (PRD §15, §16; Phase 8.)

## Deferred Requirements

These have a concrete scope and promotion gate in [BACKLOG.md](BACKLOG.md); they are not part of core MVP or automatically executable after Phase 8.

- **PR12**: Selected-item replay/manual resolution requires current-state/outcome re-check, original identity, explicit confirmation, reason and actor/time/old-new-state/correlation audit. Owner: B01.
- **DEPLOY-01**: Optional AWS/Terraform learning uses an explicitly authorized sandbox, private resources, cost ceiling, least-privilege/access review and a tested destroy plan. Owner: B02; PRD §20.
- **K8S-01**: Optional Kubernetes requires a concrete learning goal, bounded resources and working health checks after simpler hosting. Owner: B03; PRD §20.

## Out of Scope

| Feature | Reason |
|---------|--------|
| Real financial endpoints, money movement, account/customer data | Synthetic teaching model only |
| Ledger, settlement finality, actual scheme/ISO 20022 or regulatory claims | Excluded by PRD §1 |
| Production SaaS, multi-tenancy, HA and public exposure in MVP | Local-first learning and resource constraints |
| Manual payment entry replacing CLI | Generator is the required primary producer |
| Bulk automatic replay of pending outcomes | Unknown outcomes require identity-preserving reconciliation |
| Automatic rejection merely because a timeout elapsed | Absence of a reply is not definitive business evidence |

## Traceability

Every roadmap requirement has exactly one owning phase. Subsequent phases re-test earlier contracts without changing ownership.

| Requirement | Phase | Status |
|-------------|-------|--------|
| SK01 | Phase 1 | Pending |
| SK02 | Phase 1 | Pending |
| SK03 | Phase 1 | Pending |
| SK04 | Phase 1 | Pending |
| SK05 | Phase 1 | Pending |
| PR02 | Phase 1 | Pending |
| PR04 | Phase 1 | Pending |
| PR10 | Phase 1 | Pending |
| API-01 | Phase 1 | Pending |
| API-02 | Phase 1 | Pending |
| DATA-01 | Phase 1 | Pending |
| ENV-01 | Phase 1 | Pending |
| LEARN-01 | Phase 1 | Pending |
| PR03 | Phase 2 | Pending |
| MSG-01 | Phase 2 | Pending |
| OUT-01 | Phase 2 | Pending |
| OUT-02 | Phase 2 | Pending |
| ENV-02 | Phase 2 | Pending |
| PR05 | Phase 3 | Pending |
| PR08 | Phase 3 | Pending |
| WORK-01 | Phase 3 | Pending |
| WORK-02 | Phase 3 | Pending |
| STATE-01 | Phase 3 | Pending |
| DATA-02 | Phase 3 | Pending |
| LEARN-02 | Phase 3 | Pending |
| PR01 | Phase 4 | Pending |
| GN01 | Phase 4 | Pending |
| GN02 | Phase 4 | Pending |
| GN03 | Phase 4 | Pending |
| GN04 | Phase 4 | Pending |
| GN05 | Phase 4 | Pending |
| GN06 | Phase 4 | Pending |
| GN07 | Phase 4 | Pending |
| GN08 | Phase 4 | Pending |
| GEN-01 | Phase 4 | Pending |
| GEN-02 | Phase 4 | Pending |
| TEST-01 | Phase 4 | Pending |
| PR06 | Phase 5 | Pending |
| RETRY-01 | Phase 5 | Pending |
| QUAR-01 | Phase 5 | Pending |
| QUAR-02 | Phase 5 | Pending |
| PR07 | Phase 6 | Pending |
| REC-01 | Phase 6 | Pending |
| REC-02 | Phase 6 | Pending |
| REC-03 | Phase 6 | Pending |
| PR09 | Phase 7 | Pending |
| FAIL-01 | Phase 7 | Pending |
| FAIL-02 | Phase 7 | Pending |
| OBS-01 | Phase 7 | Pending |
| OBS-02 | Phase 7 | Pending |
| OPS-01 | Phase 7 | Pending |
| SAFE-01 | Phase 7 | Pending |
| OPS-02 | Phase 7 | Pending |
| LEARN-03 | Phase 7 | Pending |
| PR11 | Phase 8 | Pending |
| UI-01 | Phase 8 | Pending |
| UI-02 | Phase 8 | Pending |
| UI-03 | Phase 8 | Pending |
| UI-04 | Phase 8 | Pending |
| UI-05 | Phase 8 | Pending |
| UI-06 | Phase 8 | Pending |

**Coverage:**
- Roadmap requirements: 61
- Mapped to exactly one phase: 61
- Unmapped: 0
- Original source IDs: 25 total; 24 in the roadmap and PR12 in B01
- Additional roadmap detail IDs: 37
- Scenario coverage: SC01–SC12 in VALIDATION.md

## Baseline Notes

SK01/SK03/SK04, PR02/PR04/PR10 and GN01–GN06 have partial or existing starter behavior. The amount cap, database semantics, full configurable CLI limits and downstream integration still require acceptance. See [BASELINE.md](codebase/BASELINE.md); no distributed-system gate is marked complete.

---
*Last updated: 2026-10-04 after source-to-phase coverage review.*

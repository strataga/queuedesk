# Validation and Acceptance: QueueDesk

## Evidence Rules

Requirements are complete only after implementation and verification, with source/tests/configuration committed as part of the relevant feature work. The planning setup itself completes no product phase.

- Fast Java tests prove rules; real PostgreSQL proves migrations, races, rollback and durability; approved real IBM MQ proves persistence, local JMS transaction and backout behavior.
- Count distinct instruction IDs, stable event IDs, admission events and definitive outcomes. Queue message counts alone are insufficient.
- Use test hooks/latches at crash boundaries, bounded eventual assertions and useful failure diagnostics. Fixed sleeps are not sole synchronization.
- Store runId, seed, fixture version, effective limits, software versions, timestamps, fault point, counts, identity assertions, pending items, timing intervals and pass/fail/skip status with scenario evidence.
- Full report output may live in ignored reports/; retain compact, reproducible acceptance summaries and exact invocation/configuration in versioned verification docs. Never commit secrets.
- A skipped or unavailable integration profile is not a passed gate. An in-process adapter is explicitly simulation evidence.
- Learner explanation is verified once at the final project review, not per phase; passing automation alone does not establish understanding.

## Baseline Verification

During planning intake on 2026-10-04, `./mvnw test` passed five tests with zero failures, errors or skips. This validates the current in-memory starter only.

docs/VERIFICATION.md records earlier package/smoke/demo evidence. Those checks were not rerun during intake. That intake-time gap is closed: real Postgres 17 / IBM MQ evidence plus worker, reconciliation, quarantine, dashboard and kind acceptance are recorded in docs/VERIFICATION.md (Phases 1–9).

## Scenario Ownership

Each scenario has one final acceptance owner. Earlier phases may establish prerequisites or run a preliminary version.

| ID | Owning phase | Setup | Required proof |
|----|--------------|-------|----------------|
| SC01 | 4 | Normal, seed 42, 20 valid unique instructions | 20 distinct admissions and COMPLETED_SIMULATED outcomes within 90 seconds; zero unresolved |
| SC02 | 4 | 20 unique plus five identical key/body repeats | 25 attempts, 20 instruction rows, 20 admission events; repeated requests return original IDs |
| SC03 | 4 | Changed amount under an existing key | 409; original immutable payload and outcome unchanged |
| SC04 | 4 | Zero/negative amount, wrong currency, missing participant | 400 each; zero corresponding instruction/outbox rows |
| SC05 | 4 | Up to 100 at capped rate/concurrency | Every attempt accounted for; backlog rises/drains; timings and resource measurements retained |
| SC06 | 7 | MQ unavailable after admission, then process restart and recovery | Honest durable RECEIVED/pending state, original event IDs retained and eventual drain without loss |
| SC07 | 7 | Controlled repeated delivery of one eventId | One durable result/dedup record; visible duplicate evidence; no state regression |
| SC08 | 7 | Crash between worker DB and JMS commits | Safe redelivery after restart; original durable result remains unchanged |
| SC09 | 7 | Fake receiver result saved, confirmation lost | Pending original instruction reconciles to receiver truth; no replacement identity or rerolled result |
| SC10 | 7 | Poison schema and unavailable backout route | Held work with reason; bad routing preserves persistent work; projection freshness remains honest |
| SC11 | 4 | Deterministic mock clearing/receiver rejection | REJECTED with stable reason; normal acknowledgement, no transport retry/quarantine |
| SC12 | 4 | User stop or sending/observation deadline | New submissions stop; unsent/pending/unknown work remains listed; no fabricated success |
| RS01 | B04 | Benign normal/unusual activity | Unnecessary holds/rejections reported; unusual is not fraud |
| RS02 | B04 | Labeled suspicious velocity/conflict | Rules compute numeric facts; missed-suspicious rate measured |
| RS03 | B04 | HTTP retries and MQ redeliveries | No inflated velocity or auto-fraud label; stable identities, cached assessment |
| RS04 | B04 | Missing/contradictory/adversarial memo | Injection cannot alter policy/labels/destinations; uncertain holds |
| RS05 | B04 | Provider timeout/invalid/rate-limit/wrong model | NOT_ASSESSED hold; bounded calls; no receiver invocation |
| RS06 | B04 | Restart around assessment/hold commit | No second outcome; incomplete assessment visible and recoverable |

SC07 is first exercised in Phase 3; SC10 in Phase 5; SC09 in Phase 6. Phase 7 owns their final recovery acceptance against the assembled system. RS01–RS06 are owned by backlog item B04 and run only if L5 is selected.

## Walking Skeleton Gate — Phase 1

1. Plain Java tests cover amount 1, 10,000,000, 0, negative, missing, fractional and over-cap inputs, synthetic participant rules and semantic normalization.
2. Real HTTP tests cover 202/200/400/404/409/413/503, Location/statusUrl, safe errors, unknown fields and bounded list/status/cursor validation.
3. Real Postgres tests cover initial/repeated migration, unique-key concurrency, same-key changed-body race, commit failure and restart retrieval.
4. Invalid requests create no rows. Admission commit failure produces no successful 202. Correlation changes never create a new business instruction.
5. API restart preserves the exact instruction ID/state. Document startup, migration, tests and non-destructive shutdown.
6. Learner-review material is prepared (small-change exercise, failing test, prompts); the human review is deferred to the final project review.

## Core MVP Gate — Phases 2–4

1. Admission/outbox rollback leaves neither row; exact repeats produce one original event.
2. MQ publish uses persistent JSON and local JMS transaction. A send-to-marker crash reuses eventId.
3. Worker result/dedup commits together before JMS commit. Repeated eventId produces one result.
4. SC01–SC05 and SC11–SC12 pass with separate admission and completion reports.
5. PR01–PR05, PR08 and PR10 pass; a reader can run the local loop without DB editing.
6. Learner-review material is prepared (path walkthrough, dedup explanation, identity prompts); the human review is deferred to the final project review.

## Failure Matrix — Final Owner Phase 7

| Boundary or failure | Injection/control | Durable assertions | Recovery assertions |
|---------------------|-------------------|--------------------|---------------------|
| DB unavailable at admission | Stop only named lab DB or controlled connection failure | No successful admission claimed; any ambiguous response retains original key/body | 503 when possible; same-key retry yields at most one identity |
| Admission fails after instruction insert | Test transaction hook before commit | Instruction and outbox both absent | A later valid submission can be admitted |
| MQ down after DB admission | Stop named queue manager after successful admission | RECEIVED plus pending outbox survives API restart | Backoff then drain with original eventId |
| Publisher dies after MQ commit, before marker | Hook/latch at exact window | Outbox may remain pending though MQ holds a message | Resend same eventId; one final outcome, duplicate visible |
| Publisher cannot mark published | DB failure after confirmed MQ commit | Broker work retained; no fabricated marker | Later retry/dedup safe; outcome independent of marker |
| Worker dies before DB commit | Controlled exit before worker commit | No committed worker result/dedup | JMS delivery redelivers; one eventual result |
| Worker dies after DB commit, before JMS commit | Controlled exit after durable result | Result and processed_message retained | Redelivery dedup; no repeated effect/state regression |
| Worker DB fails during processing | DB transaction failure | No acknowledgement of uncommitted result | Rollback/redelivery after recovery |
| Classified transient receiver failure | Deterministic lab fixture | Current business state retained; counters separate | Bounded retries; lookup first if effect may have applied |
| Receiver commits, then worker/response fails | Independent receiver result plus loss/crash hook | One receiver truth survives worker rollback | Original-ID lookup/repeat recovers same truth |
| Receiver lookup remains unknown | Deterministic unknown fixture | PENDING_CONFIRMATION and durable task retained | Bounded scheduled lookup; no expiry-based rejection |
| Malformed/unsupported message | Local lab fixture | No fabricated business success/rejection | Durable quarantine with stable reason |
| Backout route unavailable | Named route/permission fault | Original persistent work preserved | Rollback or stopped consumer, diagnostic evidence, repair then progress |
| DB down during quarantine projection | Controlled projection write failure | MQ-held work still exists; stale/unavailable view explicit | Projection reconciliation catches up, no zero-from-error |
| Process restart during reconciliation | Exit around task/lookup/update boundaries | One unique task and original identity | Idempotent resume; no duplicate receiver result |
| Backlog/disk guard activates | Controlled measured threshold fixture | Accepted records/events intact | Stop new generated work, report unresolved, recover without deleting data |

## Reliability Gate — Phases 5–7

- PR06, PR07 and PR09 plus SC06–SC10 and the full matrix pass.
- Attempt thresholds and one poison-routing owner are measured on the selected IBM JMS/Spring combination.
- TG02/TG03 pass: trace context survives outbox/MQ/restart with per-attempt spans, and exporter outage degrades visibly without blocking work.
- Pending and quarantine state remain observable through outage/restart and never become false rejection/completion.
- Structured logs, bounded metric labels, latency definitions, freshness, liveness and dependency state behave as documented.
- Capacity/resource safeguards and named-resource shutdown work; tests refuse non-lab targets.
- Learner-review material is prepared (run demonstrations, crash-window explanations); the human review is deferred to the final project review.

## Dashboard Gate — Phase 8

- Verify overview, filtered list, detail timeline, run detail and operations views against API fixtures and the assembled backend.
- Cover bounded pagination, run/status/time filters, unknown IDs, empty data, API errors, stale projections and recovery.
- Confirm hidden-view polling stops and visible polling resumes at the five-second interval without overlapping runaway requests.
- Keyboard navigation, labels, focus and status text/icons work; color is supplementary.
- Format currency from integer minor units; show the simulation label permanently.
- Preserve completed business outcome with a lagging published marker; missing intermediate polls are not invented events.
- No replay/manual-entry or other operator writes are exposed.

## Kubernetes Gate — Phase 9

- Liveness/readiness/startup probes use Phase 7 health endpoints; dependency outages cause no destructive restart loops.
- Pod restarts, rollout/restart and cluster recreation preserve Postgres durability and duplicate/recovery guarantees; scenario behavior is unchanged through restarts.
- MQ topology is pinned and persistent; broker restarts recover via the proven outbox path.
- Scoped teardown removes only named QueueDesk resources; retained data handling is documented; no cloud spend occurs.

## Final Review — after Phase 9

The single human gate for the project. The user walks the assembled system and
closes LEARN-01, LEARN-02 and LEARN-03 in one session:

- LEARN-01: explain records, injection, layers, constraints, commit and restart survival.
- LEARN-02: trace DB/JMS commits, explain the transaction/MQ boundary and dedup keys.
- LEARN-03: demonstrate normal/duplicate/outage runs, explain a crash, reconcile a loss.
- Full walkthrough: admit → publish → work → recover → dashboard → local Kubernetes.

## Checks by Layer

| Layer | Initial owner | Required check |
|-------|---------------|----------------|
| Plain Java and HTTP | 1 | Run on each feature change; validation, hashing, errors, read contract |
| Real Postgres | 1 | Migration, restart, unique constraints, rollback; expanded for outbox/dedup/reconciliation |
| Real MQ | 2 | Explicit integration profile/job, persistent publish, local commit/rollback; expanded for consumer/backout |
| CLI end-to-end | 4 | Core scenario assertions and honest reports |
| Controlled failure integration | 7 | Complete failure matrix and SC06–SC10, then all-scenario regression |
| Browser/UI | 8 | Read-only views, freshness, polling and keyboard behavior |
| Kubernetes | 9 | Pod restart/rollout/storage/teardown and scenario regression |

## Completion Recording

For each phase, its executable plans supply concrete verification commands and must-haves. After execution, write phase verification/UAT evidence, update requirement checkboxes and roadmap/state, and retain unresolved findings. Do not mark a gate passed because the source documents or diagrams describe it.

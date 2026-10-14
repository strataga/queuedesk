# Roadmap: QueueDesk

## Overview

Build from the existing Java/in-memory HTTP rehearsal to a durable local messaging lab, prove safe recovery, then add a read-only Angular dashboard. All phases are planned at roadmap/context level; executable plans are intentionally created when each phase's dependencies and compatibility research are available.

## Delivery Gates

| Gate | Phases | Required evidence |
|------|--------|-------------------|
| L1 durable walking skeleton | 1 | Commit-before-202, invalid-input zero rows, database idempotency, bounded reads, restart survival and explanation |
| L2 core MVP | 2–4 | Atomic outbox, real MQ/worker/dedup, SC01–SC05 and SC11–SC12 reports and explainable local runbook |
| L3 reliability | 5–7 | Bounded quarantine, pending reconciliation, SC06–SC10 and complete crash/outage matrix |
| L4 optional dashboard | 8 | Read-only API-based views, correct freshness/uncertainty and accessible polling UI |
| Local Kubernetes (optional) | 9 | Working health checks, restart/rollout survival, persistent data and scoped teardown |

## Phases

- [ ] **Phase 1: Durable walking skeleton** - A generated instruction is durably admitted, repeatable by key, and retrievable after the API restarts.
- [ ] **Phase 2: Outbox and MQ publishing** - Every accepted instruction has durable event intent that reaches approved local MQ and survives broker interruption.
- [ ] **Phase 3: Worker outcomes and deduplication** - An admitted instruction reaches one truthful simulated outcome even when its event is delivered repeatedly.
- [ ] **Phase 4: Generator reports and core MVP** - Every core traffic scenario runs with admission, completion, rejection and uncertainty accounted in a reproducible report.
- [ ] **Phase 5: Bounded retry and quarantine** - Transient work retries predictably and poison work remains inspectable without a hot loop or silent loss.
- [ ] **Phase 6: Pending confirmation and reconciliation** - A lost receiver reply can be resolved using the original instruction without creating or rerolling a second simulated outcome.
- [ ] **Phase 7: Recovery proof and operations** - The operator can demonstrate and diagnose recovery from every specified outage and crash without losing accepted work or inventing completion.
- [ ] **Phase 8: Read-only Angular dashboard** - A local operator can inspect runs, instructions and recovery evidence in an accessible dashboard that states what is current, stale or unknown.
- [ ] **Phase 9: Local Kubernetes deployment** - The backend lab runs on local Kubernetes with working health checks, surviving pod restarts and rollouts without losing durable work or duplicating outcomes.

## Phase Details

### Phase 1: Durable walking skeleton

**Goal**: A generated instruction is durably admitted, repeatable by key, and retrievable after the API restarts.
**Depends on**: Nothing (existing starter is the baseline)
**Stage**: L1
**Requirements**: SK01, SK02, SK03, SK04, SK05, PR02, PR04, PR10, API-01, API-02, DATA-01, ENV-01, LEARN-01
**Success Criteria** (what must be TRUE):

1. A new reader starts one local Postgres and the API, applies migrations, explicitly sends a synthetic instruction and retrieves the same RECEIVED record after an API restart.
2. Invalid fields/participants/amounts create no rows; oversized bodies return 413; failed commits return no successful admission. Valid amount boundaries match 1..10,000,000 minor units.
3. Concurrent same-key submissions yield one durable identity; changed business payload returns 409 without overwriting it, while changed correlation metadata remains a repeat.
4. GET supports unknown-ID 404 and bounded run/status pagination with stable ordering; database/capacity errors expose safe, truthful responses.
5. Dry-run/local defaults and bounded configuration remain usable, and learner-review material is prepared for record validation, injected layers, the unique constraint and commit-before-202.

**Plans**: TBD — source-driven plan slices captured in phase context; run `$gsd-plan-phase 1` to produce executable plans.

**Context**: [01-CONTEXT.md](phases/01-durable-walking-skeleton/01-CONTEXT.md)
**Canonical refs**: `docs/QueueDesk_PRD.md §§3–4, 7, 9, 11, 16–20`; `docs/MILESTONES.md`; `docs/images/QueueDesk_Spring_Layers.png`; `docs/images/QueueDesk_Learning_Path.png`
**Validation**: Real Postgres migration, unique-key race, invalid-input zero rows, rollback/commit failure, HTTP size/read contracts and application restart survival. Existing five tests remain baseline only.

### Phase 2: Outbox and MQ publishing

**Goal**: Every accepted instruction has durable event intent that reaches approved local MQ and survives broker interruption.
**Depends on**: Phase 1
**Stage**: L2
**Requirements**: PR03, MSG-01, OUT-01, OUT-02, ENV-02
**Success Criteria** (what must be TRUE):

1. A successful admission stores exactly one instruction and one InstructionReceived outbox event; injected rollback stores neither and exact repeats create no new event.
2. The operator can inspect a persistent versioned UTF-8 JSON message with stable eventId/instructionId and the documented correlation/run metadata.
3. MQ downtime leaves admitted work pending across restart, with visible attempts/due times; recovery drains a bounded publisher batch without losing identities.
4. A publisher crash after MQ commit preserves the same eventId on resend, and the published marker is written only after confirmed MQ commit.
5. The recorded server/client/Jakarta/platform/license checks and resource-limited local configuration make the MQ setup reproducible.

**Plans**: TBD — source-driven plan slices captured in phase context; run `$gsd-plan-phase 2` to produce executable plans.

**Context**: [02-CONTEXT.md](phases/02-outbox-and-mq-publishing/02-CONTEXT.md)
**Canonical refs**: `docs/QueueDesk_PRD.md §§5, 8–10, 12, 16–17, 20`; `docs/images/QueueDesk_Architecture.png`; `docs/images/QueueDesk_Data_Model.png`; `docs/images/QueueDesk_Outbox_Recovery.png`
**Validation**: Real Postgres atomicity plus approved IBM MQ persistent send/local transaction evidence. Preliminary SC06 and publisher crash evidence is extended to one final outcome in Phases 3 and 7.

### Phase 3: Worker outcomes and deduplication

**Goal**: An admitted instruction reaches one truthful simulated outcome even when its event is delivered repeatedly.
**Depends on**: Phase 2
**Stage**: L2
**Requirements**: PR05, PR08, WORK-01, WORK-02, STATE-01, DATA-02, LEARN-02
**Success Criteria** (what must be TRUE):

1. One consumer performs mock clearing and receiver behavior, producing COMPLETED_SIMULATED or an explicit REJECTED reason; rejection is normally acknowledged.
2. Repeated event delivery leaves one processed_message and one definitive result with visible dedup evidence, without regressing the instruction state.
3. Database business result and dedup commit together before JMS commit; database failure leaves the delivery eligible for redelivery.
4. ID/run reads show business status, reason, delivery detail and timestamps without inferring completion from queue depth or the outbox marker.
5. Learner-review material is prepared for the separate DB/JMS boundaries and tracing one stable business/event identity through the diagram, rows and logs.

**Plans**: TBD — source-driven plan slices captured in phase context; run `$gsd-plan-phase 3` to produce executable plans.

**Context**: [03-CONTEXT.md](phases/03-worker-outcomes-and-dedup/03-CONTEXT.md)
**Canonical refs**: `docs/QueueDesk_PRD.md §§5–6, 8–9, 11–12, 16, 18–19`; `docs/images/QueueDesk_Mock_Participants.png`; `docs/images/QueueDesk_Status_Model.png`; `docs/images/QueueDesk_Data_Model.png`; `docs/images/QueueDesk_Spring_Layers.png`
**Validation**: Real MQ redelivery plus real Postgres result/dedup atomicity; SC07 and SC11 capability evidence; no false completion while marker publication lags.

### Phase 4: Generator reports and core MVP

**Goal**: Every core traffic scenario runs with admission, completion, rejection and uncertainty accounted for in a reproducible report.
**Depends on**: Phase 3
**Stage**: L2 exit
**Requirements**: PR01, GN01, GN02, GN03, GN04, GN05, GN06, GN07, GN08, GEN-01, GEN-02, TEST-01
**Success Criteria** (what must be TRUE):

1. A seed-42 normal run produces 20 distinct COMPLETED_SIMULATED outcomes within its 90-second observation window, with no unresolved instruction.
2. The duplicate scenario reports 25 attempts for 20 unique instructions/events; conflict and invalid scenarios preserve original data and create no prohibited rows.
3. Dry-run, loopback, identity-preserving retry and workload limits remain enforced; burst and stop/deadline cases account for every planned, attempted and unresolved submission.
4. Reports preserve seed/fixture/configuration/run metadata, separate HTTP/end-to-end latency distributions and honest exit codes; observation polls every two seconds independently of sending.
5. SC01–SC05 and SC11–SC12 pass with stored evidence, and a new reader can run the full local CLI-to-outcome loop without manual DB editing.

**Plans**: TBD — source-driven plan slices captured in phase context; run `$gsd-plan-phase 4` to produce executable plans.

**Context**: [04-CONTEXT.md](phases/04-generator-and-core-mvp/04-CONTEXT.md)
**Canonical refs**: `docs/QueueDesk_PRD.md §§4, 13–14, 16–17, 21–22`; `README.md`; `docs/images/QueueDesk_Architecture.png`
**Validation**: SC01–SC05, SC11 and SC12 with request counts, distinct identities, outbox counts, outcome counts, unresolved records and separate latency intervals. Core MVP gate only; reliability gate remains open.

### Phase 5: Bounded retry and quarantine

**Goal**: Transient work retries predictably and poison work remains inspectable without a hot loop or silent loss.
**Depends on**: Phase 4
**Stage**: L3
**Requirements**: PR06, RETRY-01, QUAR-01, QUAR-02
**Success Criteria** (what must be TRUE):

1. Classified transient processing failures use paced bounded retries with documented attempt counts; business rejections are not technical retries.
2. Malformed/unsupported events reach a durable operational hold with stable reason after the configured maximum of three failed processing deliveries.
3. When the backout route is unavailable, work is retained by rollback or consumer stop and an operator can diagnose the routing failure.
4. Quarantine observations reconcile into an API-readable projection whose stale/unavailable state remains explicit during DB outages.

**Plans**: TBD — source-driven plan slices captured in phase context; run `$gsd-plan-phase 5` to produce executable plans.

**Context**: [05-CONTEXT.md](phases/05-bounded-retry-and-quarantine/05-CONTEXT.md)
**Canonical refs**: `docs/QueueDesk_PRD.md §§8–9, 12, 16–17`; `docs/images/QueueDesk_Data_Model.png`; `docs/images/QueueDesk_Status_Model.png`
**Validation**: Real MQ poison/backout tests including unavailable route and DB-down projection; preliminary SC10 with retained message evidence and precise attempt counts.

### Phase 6: Pending confirmation and reconciliation

**Goal**: A lost receiver reply can be resolved using the original instruction without creating or rerolling a second simulated outcome.
**Depends on**: Phase 5
**Stage**: L3
**Requirements**: PR07, REC-01, REC-02, REC-03
**Success Criteria** (what must be TRUE):

1. The fake receiver commits one durable result per instructionId independently before a deliberately lost response, and repeated calls return the original result.
2. The worker atomically records PENDING_CONFIRMATION, handled-event identity and a unique reconciliation task after a lost reply.
3. Reconciliation survives restart, looks up the original identity and resolves success or rejection only from definitive receiver evidence.
4. Repeated unknown lookups remain pending with visible age/attempts; elapsed time alone never causes rejection or a replacement instruction.

**Plans**: TBD — source-driven plan slices captured in phase context; run `$gsd-plan-phase 6` to produce executable plans.

**Context**: [06-CONTEXT.md](phases/06-pending-confirmation-and-reconciliation/06-CONTEXT.md)
**Canonical refs**: `docs/QueueDesk_PRD.md §§6, 9, 11–12, 14, 16–17`; `docs/images/QueueDesk_Status_Model.png`; `docs/images/QueueDesk_Mock_Participants.png`
**Validation**: SC09 with one durable receiver_result, one original instruction, stable handled event, restartable task and no replacement IDs; unknown and definitive rejection variants.

### Phase 7: Recovery proof and operations

**Goal**: The operator can demonstrate and diagnose recovery from every specified outage and crash without losing accepted work or inventing completion.
**Depends on**: Phase 6
**Stage**: L3 exit
**Requirements**: PR09, FAIL-01, FAIL-02, OBS-01, OBS-02, TEL-01, TEL-02, OPS-01, SAFE-01, OPS-02, LEARN-03
**Success Criteria** (what must be TRUE):

1. SC06–SC10 and both publisher/worker crash boundaries recover with stable IDs, retained rows and one definitive result; broken quarantine routing produces no silent drop.
2. The local-only harness uses controlled hooks and bounded eventual assertions, refuses non-lab targets and only operates named QueueDesk resources.
3. Logs/metrics expose backlog age, dedup/failure counts, pending age, quarantine freshness and correctly separated latency, without unbounded ID labels. Trace context survives outbox/MQ with per-attempt spans and async links, and exporter failure degrades visibly without blocking processing.
4. Resource/capacity guards stop new generated traffic at configured backlog/disk limits; health distinguishes dependency outage from a dead process and preserves restartable pending work.
5. A fresh-reader runbook and recorded demonstration pass the reliability gate; CI explicitly distinguishes real Postgres/MQ passes from skips.

**Plans**: TBD — source-driven plan slices captured in phase context; run `$gsd-plan-phase 7` to produce executable plans.

**Context**: [07-CONTEXT.md](phases/07-recovery-proof-and-operations/07-CONTEXT.md)
**Canonical refs**: `docs/QueueDesk_PRD.md §§12, 14, 16–17, 19–22`; `docs/images/QueueDesk_Outbox_Recovery.png`; `docs/images/QueueDesk_Data_Model.png`; `docs/VERIFICATION.md`
**Validation**: Full failure matrix plus SC01–SC12 regression, real Postgres/MQ results, crash-point snapshots, one-outcome assertions, pending/quarantine freshness, scoped shutdown and learning demonstration.

### Phase 8: Read-only Angular dashboard

**Goal**: A local operator can inspect runs, instructions and recovery evidence in an accessible dashboard that states what is current, stale or unknown.
**Depends on**: Phase 7
**Stage**: L4 optional
**Requirements**: PR11, UI-01, UI-02, UI-03, UI-04, UI-05, UI-06
**Success Criteria** (what must be TRUE):

1. Overview cards and an instruction list show scoped counts, amounts, age and run/status/time filters with bounded pagination and last-refresh timestamps.
2. Instruction detail shows immutable input, IDs, reason and an ordered business/transport timeline, including completed outcomes with lagging publish markers.
3. Run detail accounts for expected unique, admitted, completed, rejected, repeated and unresolved work from API-backed run evidence.
4. Operational screens show backlog, MQ availability and quarantine freshness; five-second polling stops while hidden and loading/empty/unavailable/stale states remain distinct.
5. Keyboard operation, text/icon status labels, integer-based currency formatting and a permanent simulated label work throughout the read-only experience.

**Plans**: TBD — source-driven plan slices captured in phase context; run `$gsd-plan-phase 8` to produce executable plans.
**UI hint**: yes

**Context**: [08-CONTEXT.md](phases/08-read-only-angular-dashboard/08-CONTEXT.md)
**Canonical refs**: `docs/QueueDesk_PRD.md §§4, 6–7, 15–17, 20`; `docs/images/QueueDesk_Architecture.png`; `docs/images/QueueDesk_Status_Model.png`
**Validation**: Read API integration checks and browser checks for all five views, bounded filtering/pagination, stale/unavailable semantics, visibility-aware polling, keyboard access and integer currency.

### Phase 9: Local Kubernetes deployment

**Goal**: The backend lab runs on local Kubernetes with working health checks, surviving pod restarts and rollouts without losing durable work or duplicating outcomes.
**Depends on**: Phase 7 (health/recovery behavior); Phase 8 dashboard included only if already built.
**Stage**: Optional deployment
**Requirements**: K8S-01
**Success Criteria** (what must be TRUE):

1. A local cluster (kind, k3d or minikube) runs the API/publisher/worker with explicit CPU/memory requests and limits inside a documented laptop budget.
2. Liveness/readiness/startup probes use the Phase 7 health endpoints so dependency outages never cause destructive restart loops.
3. Pod restarts, controlled rollout/restart and cluster recreation preserve Postgres durability (named volume) and existing duplicate/recovery guarantees; scenario behavior is unchanged through restarts.
4. MQ runs in a pinned supported topology (in-cluster or adjacent lab container, decided at plan time) with persistent storage; broker restarts recover via the proven outbox path.
5. Scoped teardown (namespace or cluster delete) removes only named QueueDesk resources and documents retained data and remaining cost/exposure (zero for local).

**Plans**: TBD — source-driven plan slices captured in phase context; run `$gsd-plan-phase 9` to produce executable plans.

**Context**: [09-CONTEXT.md](phases/09-local-kubernetes-deployment/09-CONTEXT.md)
**Canonical refs**: `docs/QueueDesk_PRD.md §§17, 20`; `docs/images/QueueDesk_Architecture.png`
**Validation**: Pod-restart/rollout/storage/teardown checks plus scenario regression through restarts; no cloud spend.

## Deferred Chapters

All source features remain accounted for. [BACKLOG.md](BACKLOG.md) defines B01 controlled operations (PR12) and B04 L5 fraud-risk lab with prerequisites and acceptance. B03 Kubernetes was promoted to Phase 9; B02 AWS/Terraform was declined as out-of-scope (local-only lab). Promote remaining items into numbered phases only when their entry conditions are met; preserve continuous numbering.

## Progress

Execution order: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9. Phase 8 remains optional for the product's backend completion gate; Phase 9 is an optional local-deployment exercise.

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Durable walking skeleton | 1/1 | Complete (LEARN-01 at final review) | - |
| 2. Outbox and MQ publishing | done | Complete | - |
| 3. Worker outcomes and deduplication | done | Complete | - |
| 4. Generator reports and core MVP | done | Complete | - |
| 5. Bounded retry and quarantine | done | Complete | - |
| 6. Pending confirmation and reconciliation | done | Complete | - |
| 7. Recovery proof and operations | done | Complete | - |
| 8. Read-only Angular dashboard | done | Complete | - |
| 9. Local Kubernetes deployment | done | Complete | - |

All phases are complete; evidence is recorded in docs/VERIFICATION.md. Original starter evidence remains in codebase/BASELINE.md.

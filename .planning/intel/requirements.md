# Extracted requirements

Original IDs are preserved. Detailed IDs expose acceptance already present in the PRD; they do not add unrelated scope.

## REQ-sk01
- source: docs/QueueDesk_PRD.md §3, §11
- description: Plain Java instruction types validate synthetic participants, USD, and integer minor-unit amounts without Spring; align the target cap to 1..10,000,000.
- acceptance: Prove the behavior in Phase 1; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Foundation and durable admission

## REQ-sk02
- source: docs/QueueDesk_PRD.md §3, §7
- description: POST returns 202 only after the admission database transaction commits; a failed commit never produces successful admission.
- acceptance: Prove the behavior in Phase 1; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Foundation and durable admission

## REQ-sk03
- source: docs/QueueDesk_PRD.md §3, §7
- description: GET by instruction ID returns the saved instruction and truthful current state after an API restart; unknown IDs return 404.
- acceptance: Prove the behavior in Phase 1; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Foundation and durable admission

## REQ-sk04
- source: docs/QueueDesk_PRD.md §3
- description: The Java generator defaults to dry run and loopback; the same seed/options reproduce the same business payloads.
- acceptance: Prove the behavior in Phase 1; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Foundation and durable admission

## REQ-sk05
- source: docs/QueueDesk_PRD.md §3
- description: A new reader can start the API and Postgres, apply migrations, submit/read one instruction, run tests and shut down using the runbook.
- acceptance: Prove the behavior in Phase 1; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Foundation and durable admission

## REQ-pr02
- source: docs/QueueDesk_PRD.md §4, §7
- description: Bad schema, amount or participant returns 400 before persistence and creates no instruction; after outbox introduction it also creates no event.
- acceptance: Prove the behavior in Phase 1; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Foundation and durable admission

## REQ-pr04
- source: docs/QueueDesk_PRD.md §4, §7
- description: Same normalized key/body returns 200 with the original ID and current state; changed semantic payload returns 409; concurrent submissions cannot create duplicates or extra admission events.
- acceptance: Prove the behavior in Phase 1; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Foundation and durable admission

## REQ-pr10
- source: docs/QueueDesk_PRD.md §4, §17
- description: API, dependency ports and generator defaults stay local, synthetic and bounded; credentials stay outside source/logs and no real payment endpoint is targeted.
- acceptance: Prove the behavior in Phase 1; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Foundation and durable admission

## REQ-api-01
- source: docs/QueueDesk_PRD.md §7, §8
- description: Enforce an actual 16 KiB request-body limit with 413, including streamed bodies; structured 400/409/413/503 errors expose stable safe codes and no connection strings.
- acceptance: Prove the behavior in Phase 1; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: HTTP and storage contracts

## REQ-api-02
- source: docs/QueueDesk_PRD.md §7
- description: List instructions by runId and business status with bounded pagination, default limit 50 and a documented deterministic continuation/order contract.
- acceptance: Prove the behavior in Phase 1; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: HTTP and storage contracts

## REQ-data-01
- source: docs/QueueDesk_PRD.md §7, §9
- description: Migrations create durable instruction identities, a unique idempotency key, semantic request hash excluding correlation metadata, BIGINT amount checks, UTC timestamps, version and run/state indexes.
- acceptance: Prove the behavior in Phase 1; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: HTTP and storage contracts

## REQ-env-01
- source: docs/QueueDesk_PRD.md §16, §17, §20
- description: Record observed JDK/Boot/Maven/OS/CPU plus a verified supported Postgres/container combination, local resource budget, configuration examples and migration/restart procedure.
- acceptance: Prove the behavior in Phase 1; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: HTTP and storage contracts

## REQ-learn-01
- source: docs/QueueDesk_PRD.md §18, §19, §21
- description: The learner can explain records, validation, constructor injection, controller/service/repository boundaries, database constraints, transaction commit and restart survival using a small change and failing test.
- acceptance: Prove the behavior in Phase 1; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Learning checkpoints

## REQ-pr03
- source: docs/QueueDesk_PRD.md §4, §10
- description: Admission atomically commits one instruction and one outbox event; rollback leaves neither, and broker outage leaves durable pending work.
- acceptance: Prove the behavior in Phase 2; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Outbox and MQ publishing

## REQ-msg-01
- source: docs/QueueDesk_PRD.md §8
- description: Persistent UTF-8 JSON on QUEUEDESK.INSTRUCTION.IN carries schemaVersion, eventType, stable eventId/instructionId, runId, occurredAt, correlationId and validated payload.
- acceptance: Prove the behavior in Phase 2; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Outbox and MQ publishing

## REQ-out-01
- source: docs/QueueDesk_PRD.md §10
- description: One publisher polls a bounded due batch, commits a local transacted JMS send, then marks PUBLISHED in a separate DB transaction; uncertain sends reuse eventId.
- acceptance: Prove the behavior in Phase 2; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Outbox and MQ publishing

## REQ-out-02
- source: docs/QueueDesk_PRD.md §9, §10, §12
- description: Publisher failures retain pending work, persist distinct attempt/due/error metadata and use capped reconnect/backoff with jitter; backlog/capacity guards never drop stored work.
- acceptance: Prove the behavior in Phase 2; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Outbox and MQ publishing

## REQ-env-02
- source: docs/QueueDesk_PRD.md §16, §20
- description: Before MQ runs, document exact server/client/Jakarta integration compatibility, image/build CPU support and developer-use terms; configure bounded local resources and persistent storage.
- acceptance: Prove the behavior in Phase 2; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Outbox and MQ publishing

## REQ-pr05
- source: docs/QueueDesk_PRD.md §4, §11
- description: Stable event/instruction identities and a database dedup key yield one durable business outcome on repeated delivery, with visible duplicate evidence.
- acceptance: Prove the behavior in Phase 3; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Worker outcomes and deduplication

## REQ-pr08
- source: docs/QueueDesk_PRD.md §4, §6, §7
- description: Every admitted instruction is retrievable by ID and runId with business outcome, reason, delivery detail, identifiers and meaningful timestamps.
- acceptance: Prove the behavior in Phase 3; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Worker outcomes and deduplication

## REQ-work-01
- source: docs/QueueDesk_PRD.md §9, §11
- description: One consumer validates envelopes; processed_message and business updates commit in one DB transaction before the local JMS delivery commits.
- acceptance: Prove the behavior in Phase 3; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Worker outcomes and deduplication

## REQ-work-02
- source: docs/QueueDesk_PRD.md §11
- description: Deterministic local ClearingHousePolicy and receiver implementations produce simulated success or explicit business rejection; rejection commits normally and is not retried as poison.
- acceptance: Prove the behavior in Phase 3; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Worker outcomes and deduplication

## REQ-state-01
- source: docs/QueueDesk_PRD.md §6
- description: Enforce the documented business-state transitions and keep PENDING_PUBLISH/PUBLISHED/RETRYING/QUARANTINED separate; no terminal result regresses or depends on a published marker.
- acceptance: Prove the behavior in Phase 3; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Worker outcomes and deduplication

## REQ-data-02
- source: docs/QueueDesk_PRD.md §9
- description: Retain instruction/outbox/dedup identities for the lab run; processing attempts are telemetry and failed-attempt evidence survives rollback via controlled writes or logs.
- acceptance: Prove the behavior in Phase 3; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Worker outcomes and deduplication

## REQ-learn-02
- source: docs/QueueDesk_PRD.md §18, §19, §22
- description: The learner can trace DB and JMS commits, explain why @Transactional does not automatically include MQ, and show why JMSMessageID is not the business dedup key.
- acceptance: Prove the behavior in Phase 3; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Worker outcomes and deduplication

## REQ-pr01
- source: docs/QueueDesk_PRD.md §4, §13, §14
- description: The script remains the primary producer; a normal seed-42 run admits 20 distinct instructions and observes 20 COMPLETED_SIMULATED outcomes within 90 seconds.
- acceptance: Prove the behavior in Phase 4; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Generator and core MVP evidence

## REQ-gn01
- source: docs/QueueDesk_PRD.md §13
- description: Dry run prints/writes planned payloads and effective configuration without any network calls.
- acceptance: Prove the behavior in Phase 4; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Generator and core MVP evidence

## REQ-gn02
- source: docs/QueueDesk_PRD.md §13, §14
- description: The same seed/options reproduce payloads and intended fixture faults, excluding wall-clock timestamps and server-generated identities.
- acceptance: Prove the behavior in Phase 4; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Generator and core MVP evidence

## REQ-gn03
- source: docs/QueueDesk_PRD.md §13
- description: Only allowlisted loopback targets are accepted by default; redirects and nonlocal/real hosts are refused.
- acceptance: Prove the behavior in Phase 4; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Generator and core MVP evidence

## REQ-gn04
- source: docs/QueueDesk_PRD.md §13
- description: Defaults are 20 instructions, 2 requests/sec, concurrency 1, 5-second requests and a 60-second sending cap.
- acceptance: Prove the behavior in Phase 4; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Generator and core MVP evidence

## REQ-gn05
- source: docs/QueueDesk_PRD.md §13
- description: Timeout/503 recovery preserves the exact original key/body; validation and conflict errors are never retried automatically.
- acceptance: Prove the behavior in Phase 4; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Generator and core MVP evidence

## REQ-gn06
- source: docs/QueueDesk_PRD.md §13
- description: Enforce hard caps of 500 submissions, 10 requests/sec, concurrency 4 and five minutes; any cap increase is an explicit lab change.
- acceptance: Prove the behavior in Phase 4; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Generator and core MVP evidence

## REQ-gn07
- source: docs/QueueDesk_PRD.md §13
- description: Sending and observing are separate; record HTTP results then poll every two seconds with a separate 90-second completion deadline.
- acceptance: Prove the behavior in Phase 4; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Generator and core MVP evidence

## REQ-gn08
- source: docs/QueueDesk_PRD.md §13
- description: Reports retain pending/unknown results and separate HTTP success from business completion; exit 0 requires scenario assertions and unresolved work has a distinct nonzero exit.
- acceptance: Prove the behavior in Phase 4; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Generator and core MVP evidence

## REQ-gen-01
- source: docs/QueueDesk_PRD.md §13, §14, §17
- description: Reports store seed, fixture version, configuration, runId, timestamps, attempted/accepted/repeated/invalid/unknown counts, outcome totals, separate HTTP/end-to-end latency distributions and unresolved work.
- acceptance: Prove the behavior in Phase 4; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Generator and core MVP evidence

## REQ-gen-02
- source: docs/QueueDesk_PRD.md §14 SC01–SC05, SC11–SC12
- description: Scenario fixtures cover normal, 20 unique plus five duplicate attempts, changed-body conflict, invalid payloads, capped burst, business rejection and stop/deadline behavior.
- acceptance: Prove the behavior in Phase 4; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Generator and core MVP evidence

## REQ-test-01
- source: docs/QueueDesk_PRD.md §16, §22
- description: Core end-to-end evidence accounts for unique instructions, admission events and outcomes for SC01–SC05 and SC11–SC12; compile/unit/HTTP and real-Postgres checks run as explicit gates.
- acceptance: Prove the behavior in Phase 4; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Generator and core MVP evidence

## REQ-pr06
- source: docs/QueueDesk_PRD.md §4, §12
- description: Only classified transient failures retry under a bounded policy; poison reaches quarantine with a stable reason and no endless hot loop.
- acceptance: Prove the behavior in Phase 5; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Retry and quarantine

## REQ-retry-01
- source: docs/QueueDesk_PRD.md §12
- description: Document and prove a maximum of three failed processing deliveries under the selected listener/provider semantics; pacing is outside business transactions and publisher/consumer counters stay separate.
- acceptance: Prove the behavior in Phase 5; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Retry and quarantine

## REQ-quar-01
- source: docs/QueueDesk_PRD.md §8, §12
- description: Malformed/unsupported envelopes and impossible invariants are held without false business rejection; one poison-routing owner configures BOTHRESH/BOQNAME, permissions and persistent-work preservation when routing fails.
- acceptance: Prove the behavior in Phase 5; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Retry and quarantine

## REQ-quar-02
- source: docs/QueueDesk_PRD.md §9, §12
- description: A reconciled quarantine_record view identifies held events/reasons with freshness; DB projection failure never masquerades as zero quarantine or an atomic MQ/DB update.
- acceptance: Prove the behavior in Phase 5; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Retry and quarantine

## REQ-pr07
- source: docs/QueueDesk_PRD.md §4, §11
- description: An unknown receiver outcome remains pending; reconciliation looks up the original instruction ID and never silently creates a replacement instruction.
- acceptance: Prove the behavior in Phase 6; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Uncertain outcome reconciliation

## REQ-rec-01
- source: docs/QueueDesk_PRD.md §11
- description: ReceiverGateway stores one durable result per instructionId in an independent transaction before optional response loss; repeat calls return that same result.
- acceptance: Prove the behavior in Phase 6; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Uncertain outcome reconciliation

## REQ-rec-02
- source: docs/QueueDesk_PRD.md §9, §11
- description: Lost response atomically records PENDING_CONFIRMATION, processed_message and a unique reconciliation task; due/count/status persist and bounded lookup updates only the original instruction on definitive evidence.
- acceptance: Prove the behavior in Phase 6; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Uncertain outcome reconciliation

## REQ-rec-03
- source: docs/QueueDesk_PRD.md §6, §11, §12
- description: Still-unknown lookups remain pending, definitive rejection/success follows legal transitions, and uncertain transient calls reconcile before any reissue.
- acceptance: Prove the behavior in Phase 6; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Uncertain outcome reconciliation

## REQ-pr09
- source: docs/QueueDesk_PRD.md §4, §12, §16
- description: Outage/crash-window scenarios pass with retained data and repeatable evidence; restarting restores progress without deleting records or duplicating a definitive outcome.
- acceptance: Prove the behavior in Phase 7; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Recovery proof and operations

## REQ-fail-01
- source: docs/QueueDesk_PRD.md §12, §14, §16
- description: A local-only harness uses controlled hooks/latches for publisher-after-MQ-commit and worker-before/after-DB-commit crashes, MQ/DB outages, redelivery, lost replies and unavailable quarantine routes.
- acceptance: Prove the behavior in Phase 7; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Recovery proof and operations

## REQ-fail-02
- source: docs/QueueDesk_PRD.md §14, §16
- description: SC06–SC10 assert both sides of failure boundaries with bounded eventual checks, stable IDs, one outcome and no silent loss; faults refuse non-lab execution and touch only named QueueDesk resources.
- acceptance: Prove the behavior in Phase 7; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Recovery proof and operations

## REQ-obs-01
- source: docs/QueueDesk_PRD.md §17
- description: Structured logs include UTC timestamp, component, runId, instructionId, eventId, correlationId, outcome and safe reason; elapsed durations use monotonic timing.
- acceptance: Prove the behavior in Phase 7; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Recovery proof and operations

## REQ-obs-02
- source: docs/QueueDesk_PRD.md §17
- description: Expose admission/rejection/completion totals, outbox count/oldest age, dedup/failure totals, pending-confirmation count/age, quarantine count/freshness and separate latency percentiles without ID metric labels.
- acceptance: Prove the behavior in Phase 7; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Recovery proof and operations

## REQ-ops-01
- source: docs/QueueDesk_PRD.md §17
- description: Required local health endpoints distinguish process liveness from dependency availability; MQ outages do not cause liveness restart loops or hide unavailable data.
- acceptance: Prove the behavior in Phase 7; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Recovery proof and operations

## REQ-safe-01
- source: docs/QueueDesk_PRD.md §17
- description: Bound pools, container memory/logs and queue alerts; stop new generated traffic above 1,000 backlog or below configured disk reserve (proposed 10 GB), preserving pending work and an explicit shutdown path.
- acceptance: Prove the behavior in Phase 7; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Recovery proof and operations

## REQ-ops-02
- source: docs/QueueDesk_PRD.md §16, §17, §22
- description: A fresh-reader recovery runbook explains diagnosis, pending/quarantine evidence, restart/recovery, limits and scoped shutdown; CI labels real Postgres/MQ suites and never reports skipped integration tests as passed.
- acceptance: Prove the behavior in Phase 7; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Recovery proof and operations

## REQ-learn-03
- source: docs/QueueDesk_PRD.md §19, §21, §22
- description: The learner demonstrates normal, duplicate and outage runs, explains a publisher/worker crash and reconciles a lost response by the original identity, recording what changed and why.
- acceptance: Prove the behavior in Phase 7; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Recovery proof and operations

## REQ-pr11
- source: docs/QueueDesk_PRD.md §4, §15, §20
- description: After backend reliability gates, Angular presents summary, filtered list, detail timeline and freshness using the API as the source of truth.
- acceptance: Prove the behavior in Phase 8; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Read-only Angular

## REQ-ui-01
- source: docs/QueueDesk_PRD.md §15
- description: Overview cards show business-status counts, pending-publish count/oldest age and unresolved confirmations with query scope and last refresh.
- acceptance: Prove the behavior in Phase 8; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Read-only Angular

## REQ-ui-02
- source: docs/QueueDesk_PRD.md §15
- description: The instruction list filters by runId, status and time with bounded pagination and synthetic reference, integer-formatted amount/currency, status and age.
- acceptance: Prove the behavior in Phase 8; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Read-only Angular

## REQ-ui-03
- source: docs/QueueDesk_PRD.md §15
- description: Instruction detail shows identifiers, immutable request, current result/reason and ordered timeline with transport evidence separate from business events; marker lag never overwrites completion.
- acceptance: Prove the behavior in Phase 8; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Read-only Angular

## REQ-ui-04
- source: docs/QueueDesk_PRD.md §15
- description: Run detail compares expected unique, admitted, completed, rejected, duplicate and unresolved counts from persisted run metadata/API evidence.
- acceptance: Prove the behavior in Phase 8; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Read-only Angular

## REQ-ui-05
- source: docs/QueueDesk_PRD.md §15
- description: Operational views show outbox backlog, MQ state and quarantine-projection freshness; five-second polling pauses when hidden and handles loading, empty, unavailable and stale states.
- acceptance: Prove the behavior in Phase 8; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Read-only Angular

## REQ-ui-06
- source: docs/QueueDesk_PRD.md §15, §16
- description: Keyboard-accessible controls, text/icon status labels, consistent integer currency formatting and a permanent simulated label remain usable without relying on color.
- acceptance: Prove the behavior in Phase 8; see .planning/VALIDATION.md for evidence and scenario mapping.
- scope: Read-only Angular

## REQ-pr12
- source: docs/QueueDesk_PRD.md §§4, 15
- description: Future replay/manual resolution selects an exact item, confirms current state, requires reason and confirmation, preserves identity and records actor/time/old-new state/correlation.
- acceptance: Separate authority/audit design and outcome re-check before actions; no bulk automatic replay of pending work.
- scope: Deferred controlled operations; BACKLOG.md B01

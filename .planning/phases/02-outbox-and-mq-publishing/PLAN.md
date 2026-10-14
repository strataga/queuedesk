# Phase 2 Executable Plan: Outbox and MQ publishing

**Status:** In execution.
**Covers:** PR03, MSG-01, OUT-01, OUT-02, ENV-02.

## Decisions (locked for this phase)

- **D1 No IBM Spring starter.** Direct `spring-boot-starter-jms` (managed 4.0.8) plus
  `com.ibm.mq:com.ibm.mq.jakarta.client:10.0.0.5` (exact server match, verified on Central).
  Rationale: the starter 4.1.0 targets Boot 4.1.0 (we run 4.0.8); direct `MQConnectionFactory`
  + `JmsTemplate` beans are version-clean and every bean stays visible for LEARN-02.
- **D2 Locally transacted JMS sends.** `JmsTemplate` with `sessionTransacted=true` commits each
  send; a NEW database transaction then writes the published marker (never the reverse).
  Instruction state stays RECEIVED — transport markers are outbox-only in this phase.
- **D3 Envelope v1** (`InstructionReceived`): `schemaVersion:1`, `eventType`, `eventId` (UUID,
  allocated at admission, stable across resends), `instructionId`, `correlationId`, `runId`,
  `occurredAt`, `payload{clientReference,sender,receiver,amountMinor,currency,requestHash}`.
  Persistent `TextMessage` (UTF-8 JSON), default priority, no expiry; `JMSCorrelationID` set.
- **D4 Outbox table**: `outbox_id` identity PK, `event_id` UNIQUE, `instruction_id` FK,
  `event_type`, `payload` TEXT, `created_at`, `published_at` NULL, `publish_attempts`,
  `next_due_at`; partial index on unpublished rows. Due selection:
  `WHERE published_at IS NULL AND next_due_at <= now() ORDER BY outbox_id LIMIT 10
  FOR UPDATE SKIP LOCKED`.
- **D5 Backoff**: per-row `attempts/next_due_at` exponential backoff with jitter (cap 60s);
  publisher-wide consecutive-failure backoff with the same cap; in-memory counters +
  structured logs (exporter metrics arrive in Phase 7).
- **D6 Lab MQ topology**: fresh container `qdesk-mq` (loopback 1414/9443, volume `qdesk-mqm2`),
  QMGR `QDESK`, app user `app`, queue `QUEUEDESK.INSTRUCTION.IN` with `DEFPSIST(YES)`.
  Setup captured in `scripts/mq-setup.mqsc` (reproducible via `scripts/mq setup`).
- **D7 Poll scheduling** via `@Scheduled(fixedDelay)` gated on `queuedesk.publisher.enabled`
  (tests disable it and drive `publishBatch()` directly for determinism).

## Work items (in order)

### 1. Lab MQ topology
- Remove probe container/volume; create `qdesk-mq` (loopback ports, 2 GB cap, log rotation).
- `scripts/mq` helper (up/down/status/logs/setup) + `scripts/mq-setup.mqsc`.
- Verify: `dspmq` Running, queue `DEFPSIST(YES)`, host TCP to 1414.

### 2. Dependencies + outbox migration
- pom: `spring-boot-starter-jms`, `com.ibm.mq.jakarta.client:10.0.0.5`.
- `V2__outbox.sql` per D4; boot once to migrate; re-run is a no-op.

### 3. Atomic admission intent
- `AdmissionService.admit` inserts the outbox row (fresh eventId) in the SAME transaction as
  the instruction; the repeat path returns before any insert (no new event by construction).
- `OutboxRepository` (Jdbc): `insert`, `dueBatch(limit)`, `markPublished`,
  `deferOnFailure`, `countPending`, `oldestPendingAge`.
- Verify: admit → 1 instruction + 1 event; injected rollback → neither; repeat → no new event.

### 4. Publisher
- `MqConfig`: `MQConnectionFactory` (host/port/channel/qmgr/user/pass props) →
  `CachingConnectionFactory` → transacted `JmsTemplate` (persistent, defaults).
- `OutboxPublisher.publishBatch()`: due rows → send envelope → mark in new DB tx each;
  failures defer with backoff; consecutive-failure sleep; counters + structured logs.
- `MarkerWriter` seam (functional interface) so tests can fault the marker write.
- Verify: ordered send of persistent messages; marker only after MQ commit; batch bound 10.

### 5. Outage + crash proofs
- Outage: admit N → stop broker → still pending, attempts/due visible → start broker →
  bounded drain, same eventIds, zero loss. (Test shells `docker stop/start`.)
- Crash-after-commit: faulted marker → message on queue, row pending, eventId stable →
  retry marks. Browse-assert the JSON envelope (criterion 2).
- Backlog evidence: pending count + oldest age queryable; log lines per batch/failure.

### 6. Docs + close
- `docs/RUNBOOK-L2.md` (topology, setup, inspection, outage drill, ENV-02 record),
  README milestone/contract updates, MILESTONES status, close 5 checkboxes.

## Must-haves (gate)
- `./mvnw test` green against real Postgres + real MQ (no skips-as-passes).
- `./scripts/smoke` still passes (durable backend + publisher running).
- Envelope inspectable on the queue as persistent UTF-8 JSON with stable IDs.
- No app container image yet; no consumer (queue accumulates — expected, Phase 3 drains).

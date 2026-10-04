# Extracted technical constraints

These constraints come from the PRD's technical sections; there is no standalone SPEC source.

## Local synthetic boundary
- source: docs/QueueDesk_PRD.md §§1, 17, 20
- type: nfr
- content: Fake data only; loopback services; no paid cloud or real payment integration; credentials outside source/logs. Resource reserve and scoped shutdown are explicit.

## HTTP admission and reads
- source: docs/QueueDesk_PRD.md §7
- type: api-contract
- content: /api/v1/instructions; 202 only after commit; 200 exact repeat; 409 semantic conflict; 400 invalid, 413 over 16 KiB, 503 incomplete admission/capacity, 404 unknown. Bounded run/status pagination.

## Amounts and semantic identity
- source: docs/QueueDesk_PRD.md §§7, 9, 11
- type: schema
- content: USD integer minor units; target 1..10,000,000; BIGINT and unique idempotency key; canonical request hash excludes correlation metadata; keep keys for the lab run.

## Message envelope
- source: docs/QueueDesk_PRD.md §8
- type: protocol
- content: Persistent UTF-8 JSON, versioned InstructionReceived envelope, stable eventId and instructionId, runId/correlation/timestamp. No arbitrary Java object deserialization.

## Database records and commit ordering
- source: docs/QueueDesk_PRD.md §§9–11
- type: schema
- content: Instruction plus outbox are atomic at admission; processed_message plus result are atomic at handling. Independent MQ and DB commits create intentional duplicate windows. UTC timestamps and operational attempts are separate from business truth.

## State machine and uncertainty
- source: docs/QueueDesk_PRD.md §§6, 11–12
- type: protocol
- content: RECEIVED -> BUSINESS_ACCEPTED or REJECTED; BUSINESS_ACCEPTED -> COMPLETED_SIMULATED, REJECTED or PENDING_CONFIRMATION; pending resolves only on definitive evidence. Delivery detail is separate.

## Generator bounds and observations
- source: docs/QueueDesk_PRD.md §§13–14
- type: nfr
- content: Dry run; defaults 20, 2/sec, concurrency 1, 5-second request, 60-second sending cap; hard caps 500, 10/sec, concurrency 4, five minutes. Poll every two seconds with a separate 90-second observation window; unresolved results remain explicit.

## Recovery and proof
- source: docs/QueueDesk_PRD.md §§12, 16–17
- type: nfr
- content: Classified bounded retries, one poison-routing owner, no silent loss on bad backout route, durable receiver truth and reconciliation. Real Postgres/MQ integration evidence, controlled crash hooks and freshness-aware operational data.

# Source-supported direction

No accepted ADRs were supplied. These are PRD defaults adopted for planning, not claims of a separate user design discussion.

## One local codebase and gradual dependency introduction
- source: docs/QueueDesk_PRD.md §§2, 5, 20
- status: proposed
- decision: Start with one Spring Boot codebase, one publisher and one worker consumer; mock institutions are Java roles. Add Postgres before MQ and Angular after backend gates.
- scope: architecture, learning stages, resource use

## Stable identities and separate transaction boundaries
- source: docs/QueueDesk_PRD.md §§6–12
- status: proposed
- decision: Use database-backed admission idempotency and event dedup; commit outbox with admission, MQ send before marking published, and worker DB results before JMS commit.
- scope: admission, publishing, consumption, delivery evidence

## Synthetic Java generator as primary producer
- source: docs/QueueDesk_PRD.md §§1, 3, 13–14
- status: proposed
- decision: Use a seeded Java CLI, explicit sending, loopback targets and bounded work; separate HTTP observations from simulated outcomes.
- scope: generator, fixtures, reporting

## Preserve uncertainty and defer write controls
- source: docs/QueueDesk_PRD.md §§6, 11–12, 15, 20
- status: proposed
- decision: Unknown receiver outcomes remain pending and reconcile by original identity. Angular is read-only; replay and deployment have separate entry gates.
- scope: reconciliation, dashboard, deferred operations

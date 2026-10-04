# Context and visual findings

## Current starter
- source: README.md; docs/MILESTONES.md; docs/VERIFICATION.md
- Current stage: L0 plus pre-L1 HTTP rehearsal; no Postgres, MQ, worker or Angular.
- Five unit/HTTP tests passed on 2026-10-04 during this intake. Historical package and smoke results remain attributed to docs/VERIFICATION.md.
- Source inspected: domain records, AdmissionService, repository interface/map implementation, controller/error handler, properties, pom.xml, tools/Generate.java and the three test classes.
- All project inputs were untracked in an unborn main branch at intake. Planning changes must not silently stage the existing application/source assets.

## Learning progression
- source: docs/images/QueueDesk_Learning_Path.png; docs/QueueDesk_PRD.md §§2, 19–21
- Visual stages are Java/CLI, Spring/Postgres, MQ/outbox, reliability, then Angular/deployment.
- Each stage advances when the checkpoint works and the learner can explain it. Timing is adjustable.

## Architecture and participants
- source: docs/images/QueueDesk_Architecture.png; docs/images/QueueDesk_Mock_Participants.png
- CLI -> API -> Postgres instruction/outbox; publisher -> MQ -> worker -> durable result.
- Dashboard reads only through the API. API/worker may share one codebase.
- Sender, clearing service and receiver are mock roles, not a mandate for three deployed services.

## Durable identities and operations
- source: docs/images/QueueDesk_Data_Model.png
- MVP instruction/outbox relation is one-to-one; eventId is reused in processed_message.
- processing_attempt is observability history. quarantine_record is a reconciled operational projection.
- Admission and outcome each have their own atomic DB transaction.

## Business status and delivery
- source: docs/images/QueueDesk_Status_Model.png
- Clearing and receiver rejection both lead to REJECTED; a missing reply leads to PENDING_CONFIRMATION.
- Reconciliation can confirm success or rejection. Pending is never rejected just because time passes.
- Delivery states remain separate from the business result.

## Outbox recovery window
- source: docs/images/QueueDesk_Outbox_Recovery.png
- DB admission commit precedes MQ commit; the published marker comes afterward.
- Worker may finish before that marker. A crash causes resend with the same eventId and DB dedup absorbs it.
- Target semantics are at-least-once transport plus idempotent DB effects.

## Spring responsibility boundaries
- source: docs/images/QueueDesk_Spring_Layers.png
- Controller handles HTTP; service owns use cases/transaction boundaries; repository owns persistence; infrastructure wraps MQ and DB adapters.
- Constructor injection keeps dependencies visible and interfaces belong at useful boundaries.

## Companion PDF and provenance
- source: docs/QueueDesk_PRD.pdf; docs/SOURCES.json; docs/source-bundle/QueueDesk_PRD_Visual_Bundle.zip
- PDF contains 24 pages and the same SK/PR/GN/SC identifiers and amount range as the editable PRD.
- All three original source files and seven diagrams match recorded sizes and SHA-256 hashes.

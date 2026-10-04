# Phase 3: Worker outcomes and deduplication - Context

**Gathered:** 2026-10-04
**Status:** Ready for phase research and planning; execution waits for prerequisites
**Origin:** Synthesized from the supplied PRD, implementation docs and reviewed images. This is source-derived context, not a claimed discuss-phase interview.
**Requirements:** PR05, PR08, WORK-01, WORK-02, STATE-01, DATA-02, LEARN-02

<domain>
## Phase Boundary

An admitted instruction reaches one truthful simulated outcome even when its event is delivered repeatedly.

Stage: L2. Dependency: Phase 2.
</domain>

<decisions>
## Source Decisions and Planning Defaults

- **D-01:** Mock institutions are in-process interfaces/implementations in the existing backend; no real ledger or bank calls.
- **D-02:** Use eventId for dedup; JMSMessageID may change on resend.
- **D-03:** Validate envelope/schema/invariants before business execution. Unsupported work must not be acknowledged as successful; complete quarantine handling arrives in Phase 5.
- **D-04:** Record business outcome and processed_message atomically, then allow the local JMS transaction to commit.
- **D-05:** Technical attempts are observational; failed DB transaction telemetry uses a separate controlled write or logs and cannot acknowledge business work.
- **D-06:** Model delivery and business status independently. PENDING_CONFIRMATION processing is introduced in Phase 6.

These decisions reflect the PRD and documented precedence resolutions. Version/tool/library choices below remain open until researched; there are no newly asserted locked ADRs.
</decisions>

<specifics>
## Suggested Plan Slices

1. Introduce legal business-state transitions and deterministic ClearingHousePolicy/receiver abstractions with local fixture-driven outcomes.
2. Implement a single transacted listener invoking a separate DB transactional service, processed_message uniqueness and duplicate acknowledgement.
3. Add processing-attempt evidence and rich read responses; preserve original outcome and tolerate lagging published markers.
4. Prove success, business rejection, redelivery and rollback with real Postgres/MQ, and document the learning checkpoint.

These are planning inputs, not executable PLAN.md files. Resolve the open implementation choices, then create bounded plans with tasks, verification and must-haves.

## Observable Acceptance

1. One consumer performs mock clearing and receiver behavior, producing COMPLETED_SIMULATED or an explicit REJECTED reason; rejection is normally acknowledged.
2. Repeated event delivery leaves one processed_message and one definitive result with visible dedup evidence, without regressing the instruction state.
3. Database business result and dedup commit together before JMS commit; database failure leaves the delivery eligible for redelivery.
4. ID/run reads show business status, reason, delivery detail and timestamps without inferring completion from queue depth or the outbox marker.
5. The learner can explain the separate DB/JMS boundaries and trace one stable business/event identity through the diagram, rows and logs.

## Evidence

Real MQ redelivery plus real Postgres result/dedup atomicity; SC07 and SC11 capability evidence; no false completion while marker publication lags.
</specifics>

<canonical_refs>
## Canonical References

- `docs/QueueDesk_PRD.md §§5–6, 8–9, 11–12, 16, 18–19`
- `docs/images/QueueDesk_Mock_Participants.png`
- `docs/images/QueueDesk_Status_Model.png`
- `docs/images/QueueDesk_Data_Model.png`
- `docs/images/QueueDesk_Spring_Layers.png`
- `.planning/REQUIREMENTS.md` — requirement definitions and owning phase.
- `.planning/VALIDATION.md` — scenario and failure evidence.
- `.planning/INGEST-CONFLICTS.md` — target versus starter resolutions.
</canonical_refs>

<code_context>
## Existing Code and Integration Points

- `src/main/java/dev/queuedesk/domain/StoredInstruction.java`
- `src/main/java/dev/queuedesk/http/InstructionController.java`
- `src/main/java/dev/queuedesk/service/`
- `src/main/java/dev/queuedesk/repository/`

Future-phase references describe integration points that do not yet exist at intake; re-inspect actual code after dependencies complete.
</code_context>

## Research and Open Implementation Choices

- Verify the selected Spring listener transaction/acknowledgement behavior against the DB service proxy boundary.
- Specify dedup races and state versioning so duplicate processing never overwrites a definitive outcome.
- Define deterministic fixtures, reason codes and useful timestamps; avoid inventing observed intermediate states.
- Plan a safe stop/rollback path for unsupported messages before the full poison-routing phase.

<deferred>
## Deferred Scope

Full core scenario reporting is Phase 4; bounded poison handling Phase 5; independent durable receiver truth/response loss Phase 6.
</deferred>

---
*Phase: 03-worker-outcomes-and-dedup*
*Context gathered: 2026-10-04*

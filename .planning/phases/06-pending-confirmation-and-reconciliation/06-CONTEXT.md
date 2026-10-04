# Phase 6: Pending confirmation and reconciliation - Context

**Gathered:** 2026-10-04
**Status:** Ready for phase research and planning; execution waits for prerequisites
**Origin:** Synthesized from the supplied PRD, implementation docs and reviewed images. This is source-derived context, not a claimed discuss-phase interview.
**Requirements:** PR07, REC-01, REC-02, REC-03

<domain>
## Phase Boundary

A lost receiver reply can be resolved using the original instruction without creating or rerolling a second simulated outcome.

Stage: L3. Dependency: Phase 5.
</domain>

<decisions>
## Source Decisions and Planning Defaults

- **D-01:** Receiver truth is a separate durable transaction, intentionally outside the worker's result transaction.
- **D-02:** Use original instructionId for receiver calls, lookup and reconciliation; do not create new business intent from uncertainty.
- **D-03:** Mark the event handled together with PENDING_CONFIRMATION and the reconciliation task, so MQ redelivery does not repeat a business effect.
- **D-04:** Definitive receiver success/rejection can resolve pending. Unknown lookup remains pending and retry scheduling is bounded per run/cycle.
- **D-05:** Any error where receiver work might have applied requires lookup before reissue.

These decisions reflect the PRD and documented precedence resolutions. Version/tool/library choices below remain open until researched; there are no newly asserted locked ADRs.
</decisions>

<specifics>
## Suggested Plan Slices

1. Extend ReceiverGateway with independently committed receiver_result and deterministic response-loss/lookup fixtures.
2. Implement atomic pending-confirmation/event handling and unique durable reconciliation tasks.
3. Add bounded scheduled lookup, legal terminal transitions, persisted attempts/due times and unknown-state visibility.
4. Prove lost-reply, repeat-call, still-unknown and restart behavior; explain independent receiver truth versus worker truth.

These are planning inputs, not executable PLAN.md files. Resolve the open implementation choices, then create bounded plans with tasks, verification and must-haves.

## Observable Acceptance

1. The fake receiver commits one durable result per instructionId independently before a deliberately lost response, and repeated calls return the original result.
2. The worker atomically records PENDING_CONFIRMATION, handled-event identity and a unique reconciliation task after a lost reply.
3. Reconciliation survives restart, looks up the original identity and resolves success or rejection only from definitive receiver evidence.
4. Repeated unknown lookups remain pending with visible age/attempts; elapsed time alone never causes rejection or a replacement instruction.

## Evidence

SC09 with one durable receiver_result, one original instruction, stable handled event, restartable task and no replacement IDs; unknown and definitive rejection variants.
</specifics>

<canonical_refs>
## Canonical References

- `docs/QueueDesk_PRD.md §§6, 9, 11–12, 14, 16–17`
- `docs/images/QueueDesk_Status_Model.png`
- `docs/images/QueueDesk_Mock_Participants.png`
- `.planning/REQUIREMENTS.md` — requirement definitions and owning phase.
- `.planning/VALIDATION.md` — scenario and failure evidence.
- `.planning/INGEST-CONFLICTS.md` — target versus starter resolutions.
</canonical_refs>

<code_context>
## Existing Code and Integration Points

- `Phase 3 receiver abstraction and outcome service`
- `Phase 1 migrations/transaction adapter`
- `src/main/java/dev/queuedesk/http/InstructionController.java`

Future-phase references describe integration points that do not yet exist at intake; re-inspect actual code after dependencies complete.
</code_context>

## Research and Open Implementation Choices

- Specify separate transaction/proxy boundaries so receiver_result survives worker rollback in the intended test fixture.
- Choose reconciliation backoff/batch limits, crash recovery and concurrency control for task claiming/completion.
- Specify lookup unknown versus unavailable behavior and state/version checks when reconciliation races with redelivery.
- Ensure definitive rejection is tested as well as success, and pending age never acts as an automatic terminal timer.

<deferred>
## Deferred Scope

Full distributed crash/recovery proof and release evidence are Phase 7. Manual resolution remains B01.
</deferred>

---
*Phase: 06-pending-confirmation-and-reconciliation*
*Context gathered: 2026-10-04*

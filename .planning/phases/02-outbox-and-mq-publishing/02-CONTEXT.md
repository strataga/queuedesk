# Phase 2: Outbox and MQ publishing - Context

**Gathered:** 2026-10-04
**Status:** Ready for phase research and planning; execution waits for prerequisites
**Origin:** Synthesized from the supplied PRD, implementation docs and reviewed images. This is source-derived context, not a claimed discuss-phase interview.
**Requirements:** PR03, MSG-01, OUT-01, OUT-02, ENV-02

<domain>
## Phase Boundary

Every accepted instruction has durable event intent that reaches approved local MQ and survives broker interruption.

Stage: L2. Dependency: Phase 1.
</domain>

<decisions>
## Source Decisions and Planning Defaults

- **D-01:** One InstructionReceived event per instruction; eventId is allocated once and retained through every resend.
- **D-02:** Send persistent JSON through a locally transacted JMS session to QUEUEDESK.INSTRUCTION.IN.
- **D-03:** MQ and Postgres are separate resources: MQ commit precedes a new DB transaction marking PUBLISHED.
- **D-04:** Start with one publisher and a due batch of ten; retries pause/back off with jitter and keep publisher counters separate.
- **D-05:** Apply local resource and backlog guards while preserving pending rows; no cleanup-based recovery.

These decisions reflect the PRD and documented precedence resolutions. Version/tool/library choices below remain open until researched; there are no newly asserted locked ADRs.
</decisions>

<specifics>
## Suggested Plan Slices

1. Add outbox migration, stable envelope serialization and atomic admission/outbox transaction, including rollback and repeat tests.
2. Verify MQ compatibility, developer image/build platform and terms; add local bounded queue configuration and a persistent send/receive probe.
3. Implement one bounded polling publisher with local JMS send transaction, separate published-marker transaction and persistent retry metadata.
4. Prove broker outage/restart and the controlled commit-to-marker crash window; document pending-work inspection and shutdown.

These are planning inputs, not executable PLAN.md files. Resolve the open implementation choices, then create bounded plans with tasks, verification and must-haves.

## Observable Acceptance

1. A successful admission stores exactly one instruction and one InstructionReceived outbox event; injected rollback stores neither and exact repeats create no new event.
2. The operator can inspect a persistent versioned UTF-8 JSON message with stable eventId/instructionId and the documented correlation/run metadata.
3. MQ downtime leaves admitted work pending across restart, with visible attempts/due times; recovery drains a bounded publisher batch without losing identities.
4. A publisher crash after MQ commit preserves the same eventId on resend, and the published marker is written only after confirmed MQ commit.
5. The recorded server/client/Jakarta/platform/license checks and resource-limited local configuration make the MQ setup reproducible.

## Evidence

Real Postgres atomicity plus approved IBM MQ persistent send/local transaction evidence. Preliminary SC06 and publisher crash evidence is extended to one final outcome in Phases 3 and 7.
</specifics>

<canonical_refs>
## Canonical References

- `docs/QueueDesk_PRD.md §§5, 8–10, 12, 16–17, 20`
- `docs/images/QueueDesk_Architecture.png`
- `docs/images/QueueDesk_Data_Model.png`
- `docs/images/QueueDesk_Outbox_Recovery.png`
- `.planning/REQUIREMENTS.md` — requirement definitions and owning phase.
- `.planning/VALIDATION.md` — scenario and failure evidence.
- `.planning/INGEST-CONFLICTS.md` — target versus starter resolutions.
</canonical_refs>

<code_context>
## Existing Code and Integration Points

- `src/main/java/dev/queuedesk/service/AdmissionService.java`
- `src/main/java/dev/queuedesk/repository/InstructionRepository.java`
- `src/main/resources/`
- `pom.xml`

Future-phase references describe integration points that do not yet exist at intake; re-inspect actual code after dependencies complete.
</code_context>

## Research and Open Implementation Choices

- Verify exact IBM MQ server/client versions, Boot/Jakarta integration and target CPU manifest/build path before pinning.
- Review applicable developer-use terms before running the chosen image.
- Specify due-row selection and retry metadata semantics for one publisher; replicas/leases remain deferred.
- Confirm behavior on MQ commit uncertainty and database failure while writing the published marker.

<deferred>
## Deferred Scope

Worker outcomes/dedup are Phase 3. Consumer poison routing is Phase 5. Multiple publisher replicas require separate design.
</deferred>

---
*Phase: 02-outbox-and-mq-publishing*
*Context gathered: 2026-10-04*

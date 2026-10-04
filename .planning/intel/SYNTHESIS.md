# QueueDesk source synthesis

Date: 2026-10-04. Mode: new planning scaffold for an existing starter.
User direction: read the docs, PRD and images and use GSD to set up planning for all features; subsequently instructed to proceed. This authorizes document discovery and creation of the planning setup.

## Inventory and method

- 12 source documents/assets: two PRD representations and ten DOCs (README, milestones, verification, seven PNGs).
- One logical PRD: docs/QueueDesk_PRD.md, with docs/QueueDesk_PRD.pdf as its companion.
- No ADRs, no standalone SPECs, no locked ADR decisions.
- Source provenance: docs/SOURCES.json and the unchanged source ZIP.
- Applied the GSD ingest-docs classification, precedence/conflict, synthesis and roadmapping workflow inline. No typed subagent execution is claimed.
- Runtime verified with `gsd_run runtime-identity --raw`: @opengsd/gsd-core 1.13.0, globally installed under ~/.bun/install/global/node_modules/.
- Installed skills' ~/.codex/gsd-core references are stale. The global runtime and its workflow/templates were located and used without changing machine configuration.
- Reference graph has no cycles among ingested assets. The PRD's image paths resolve relative to docs/.

## Scope and coverage

- Preserve all 25 original requirement IDs: SK01–SK05, PR01–PR12, GN01–GN08.
- 24 original IDs plus 37 detailed requirements yield 61 roadmap requirements across eight phases.
- PR12 remains a deferred feature with acceptance and entry gates in BACKLOG.md.
- Preserve all 12 scenarios SC01–SC12 in VALIDATION.md, with phase ownership and evidence expectations.
- Optional AWS/Terraform and Kubernetes work is retained as gated backlog work.
- Four source-supported architectural decisions are recorded as proposed, not retroactively locked.
- Eight technical constraints are extracted from the PRD's technical sections.
- Conflicts: zero blockers, zero unresolved variants, seven informational resolutions/clarifications.

## Source-grounded context

The learner knows TypeScript/Node and OOP and is learning Java/Spring/MQ. Start with the current Java CLI and in-memory HTTP rehearsal, then prove each boundary. Correctness, evidence and the ability to explain behavior outrank throughput. No calendar or daily-hours assumption is made.

The baseline unit/HTTP suite was rerun during intake: five tests passed, zero failures/errors/skips. Existing package/smoke evidence is historical from docs/VERIFICATION.md, not rerun evidence from this planning session.

## Reading order

1. [Decisions](decisions.md)
2. [Requirements extracted from sources](requirements.md)
3. [Constraints](constraints.md)
4. [Context and visual findings](context.md)
5. [Conflict report](../INGEST-CONFLICTS.md)
6. [Project](../PROJECT.md), [requirements](../REQUIREMENTS.md), [roadmap](../ROADMAP.md), [validation](../VALIDATION.md)

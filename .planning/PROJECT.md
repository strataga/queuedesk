# QueueDesk

## What This Is

QueueDesk is a local synthetic payment messaging lab for an experienced TypeScript/Node developer learning Java, Spring Boot and IBM MQ. A seeded Java CLI submits instructions; the target backend durably admits them, publishes through an outbox, simulates clearing/receiver outcomes, and demonstrates recovery. The current application implements a Java foundation and in-memory HTTP rehearsal.

## Core Value

Trace one synthetic instruction from admission to a truthful, durable simulated outcome, and explain how duplicates, failures and uncertain confirmations are handled without creating a second business instruction.

## Requirements

### Validated baseline

- Plain Java instruction validation, constructor-injected HTTP/service/repository structure and in-memory repeat/conflict/read behavior are present.
- The finite Java generator supports deterministic dry runs and explicit bounded loopback submissions.
- Intake reran `./mvnw test`: five tests passed, zero failures/errors/skips on 2026-10-04.
- This evidence established the starter only at intake. All stages L1–L4 plus local Kubernetes are implemented and evidenced since; see docs/VERIFICATION.md.

### Active

- [x] L1 durable admission, database idempotency, bounded reads and a reproducible local runbook.
- [x] L2 atomic outbox, persistent IBM MQ delivery, one worker, mock outcomes and durable event dedup.
- [x] L2 complete CLI scenario reports with admission/outcome separation and core acceptance evidence.
- [x] L3 bounded retries/quarantine, lost-response reconciliation, controlled crash/outage proof and operational diagnosis.
- [x] L4 read-only Angular with scoped freshness, run accounting and accessible status presentation, after backend gates.
- [x] Local Kubernetes deployment exercise on kind/k3d/minikube after the reliability gate.

Detailed acceptance and ownership are in [REQUIREMENTS.md](REQUIREMENTS.md). All original SK/PR/GN identifiers are preserved; [VALIDATION.md](VALIDATION.md) owns scenario coverage.

### Out of Scope

- Real money, bank connections, real account/customer data, ledger/balances, settlement finality, payment-scheme conformance or compliance claims.
- Production SaaS, multi-tenancy, high availability, paid cloud and public deployment in the local MVP.
- Replacing the generator with manual dashboard entry.
- Operator write controls are deferred work with an explicit entry gate in [BACKLOG.md](BACKLOG.md); local Kubernetes is Phase 9. AWS/Terraform deployment is out of scope: local-only lab, no deployment plans.
- Multi-currency conversion, publisher replicas and independent microservice deployments are future design options, not implied deliverables.

## Context

- Source of product acceptance: [PRD](../docs/QueueDesk_PRD.md), companion PDF, eight reviewed source diagrams and ten read-only UI mockups in `docs/images/ui/` (ingested after the initial scaffold; risk screens are L5 scope).
- Current implementation notes: README.md and docs/MILESTONES.md. They now record database durability and MQ processing as implemented and evidenced (docs/VERIFICATION.md).
- Existing package: `dev.queuedesk`; domain, HTTP, service and repository layers; standalone `tools/Generate.java`.
- Observed pins: Java 25, Spring Boot 4.0.8 and custom checksum-pinned Maven 3.9.11 wrapper. Intake test runtime reported JDK 25.0.4.1. These are observed facts, not a new compatibility/support assessment.
- All pre-existing project files were untracked in an unborn main branch at intake. Preserve that work; planning-only commits must not include it accidentally.
- No daily study hours, deadline or employer stack versions have been supplied.

## Constraints

- **Learning sequence:** L1 before MQ; end-to-end core before reliability claims; backend evidence before Angular.
- **Architecture:** One backend codebase, one publisher and one consumer initially. Mock participants are interfaces/implementations.
- **Identity and truth:** HTTP admission, delivery evidence and simulated business outcome are distinct. Keep original idempotency key, instructionId and eventId through recovery.
- **Transactions:** Admission plus outbox and outcome plus dedup are separate atomic DB transactions. MQ commits are local; no distributed exactly-once claim.
- **Uncertainty:** A timeout is not rejection. Pending confirmations reconcile by original identity.
- **Data:** USD integer minor units, fixed synthetic participants, UTC timestamps, stable sanitized reason codes.
- **Local limits:** Loopback, dry run defaults, bounded generation, resource limits and named-resource shutdown; no secrets in source/logs.
- **Evidence:** Real PostgreSQL for transaction proof and approved compatible IBM MQ for messaging proof; mocks do not substitute for integration acceptance.
- **Versions:** Verify official support/platform/client compatibility when planning the dependency phase; preserve open decisions rather than guessing latest versions.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Use PRD as future target and README/milestones as current-state evidence | The source docs explicitly distinguish intended product from starter | Adopted for planning |
| Plan nine delivery phases through local Kubernetes | Each phase proves a distinct learning/product boundary | Roadmap created |
| Align amount cap to 1..10,000,000 in Phase 1 | PRD §11 has precedence over starter implementation notes | Planned; code unchanged during intake |
| SC02 is 20 unique plus five repeats | Preserve the PRD's distinct-business-identity acceptance | Planned in Phase 4 |
| Keep GN04 defaults and add configurable GN06 bounds | A 60-second default and five-minute maximum serve different purposes | Planned in Phase 4 |
| Keep PR12 and deployment exercises gated in backlog | PRD explicitly defers controls and requires additional authority/cost/security design | Captured |
| Use GSD global runtime via gsd_run | Installed skill references point at an absent ~/.codex/gsd-core directory | Runtime identity verified; no machine config changed |
| Preserve L5 risk lab as B04 and add tracing acceptance to Phase 7 | PRD §§17A/23–25 and post-intake UI mockups contained unplanned TG02–TG04/PR13–PR16/RS01–RS06 scope | Backlog + requirements updated; no phase renumbered |
| Promote local Kubernetes to Phase 9 | User wants k8s; local-only kind/k3d needs no cloud gate | B03 promoted; B02 unchanged |
| IBM MQ verified runnable on this machine | Spike: MQ 10.0.0.5 under emulation, put/get plus restart persistence | GO for Phase 2; native arm64 build is the fallback |
| Local-only lab, no deployment plans | User decision | B02 declined to out-of-scope; Phase 9 stays local |
| Dashboard stack: latest Angular + Tailwind | User decision | Phase 8 done; pinned Angular 22.2.1 + Tailwind 4.3.3 + Node 24.21.0 |
| No human gates until 100% | User decision | LEARN-01/02/03 verified once at final review; phases close on automation |

No accepted ADR was supplied. PRD proposals are planning defaults, not newly invented locked user decisions. Phase contexts distinguish source constraints from open implementation choices.

## Planning Workflow

The user requested the complete planning setup and then instructed us to proceed. Document discovery, synthesis and scaffold creation are authorized. GSD roles are performed inline under the installed skill's fallback; no typed subagent execution is claimed.

Configuration uses fine granularity, sequential planning/execution, research/plan checks/verifier enabled and auto-advance disabled. Codex runtime and omitted model IDs match the global preferences. The project uses an inherited model profile because the global adaptive resolver emitted a model unavailable in this session; global settings are unchanged. These are workflow defaults for this setup, not claimed user design preferences. AI integration design is disabled because the PRD contains no AI system. Source-driven contexts exist for all eight phases; executable PLAN.md files are created by `$gsd-plan-phase N` after phase-specific research.

## Evolution

After each phase, update verified requirement status, decisions, current context and STATE.md. Recheck source traceability when scope changes. Close L1, core MVP and reliability gates only with their required evidence and learner explanation. Keep later optional work explicit.

---
*Last updated: 2026-10-04 after GSD source ingestion and full-feature roadmap setup.*

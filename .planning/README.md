# QueueDesk GSD Planning

Start with [STATE.md](STATE.md), then [PROJECT.md](PROJECT.md) and [ROADMAP.md](ROADMAP.md).

- [REQUIREMENTS.md](REQUIREMENTS.md): 61 requirements, each mapped to one phase; all 25 original source IDs retained, including deferred PR12.
- [ROADMAP.md](ROADMAP.md): eight phases with dependencies and observable acceptance through optional Angular.
- [VALIDATION.md](VALIDATION.md): all 12 scenarios, failure matrix and stage gates.
- [BACKLOG.md](BACKLOG.md): controlled operations and the L5 risk lab with promotion gates (Kubernetes promoted to Phase 9; AWS/Terraform declined as out-of-scope).
- [INGEST-CONFLICTS.md](INGEST-CONFLICTS.md): resolved differences between the PRD and the starter.
- [intel/SYNTHESIS.md](intel/SYNTHESIS.md): source inventory, extracted intent and visual findings.
- [codebase/BASELINE.md](codebase/BASELINE.md): what exists and the freshly rerun test evidence.
- [intel/PLANNING-VERIFICATION.md](intel/PLANNING-VERIFICATION.md): scaffold checks and remaining runtime diagnostics.
- Each phase directory contains source-derived CONTEXT.md with proposed plan slices, canonical refs, acceptance and open research choices.

## Next GSD Action

Run `$gsd-plan-phase 1` in Codex. This produces executable plans for the durable walking skeleton using the prepared context. No feature implementation has started and no phase is complete.

## Runtime Note

`gsd_run runtime-identity --raw` identifies the existing global @opengsd/gsd-core 1.13.0 installation. Some installed skill references point to an absent ~/.codex/gsd-core directory; resolve workflow/templates from the global package at ~/.bun/install/global/node_modules/@opengsd/gsd-core/ instead.

This setup applied GSD roles inline. Typed Codex GSD agent files are absent; do not claim typed-agent guarantees. Use the skill's inline fallback when available, or repair runtime/agent installation as a separate tooling task if a future workflow requires typed dispatch.

## Revalidation

From the repository root:

```sh
gsd_run validate consistency
gsd_run roadmap validate
gsd_run roadmap analyze
gsd_run validate health
```

Health may separately report missing machine-level GSD agents. Distinguish that tooling condition from project-file consistency.

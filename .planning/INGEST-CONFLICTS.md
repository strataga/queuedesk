## Conflict Detection Report

### BLOCKERS (0)

None. No accepted ADRs, locked-decision contradictions, unknown classifications or source-reference cycles were found.

### WARNINGS (0)

None. The differences below are between target requirements and explicitly limited starter behavior, not competing PRDs.

### INFO (11)

[INFO] Authoritative target versus current implementation
  Found: README.md and docs/MILESTONES.md identify the current in-memory HTTP rehearsal and explicitly defer durable L1.
  Note: docs/QueueDesk_PRD.md remains authoritative for future acceptance. Current tests are baseline evidence, not evidence that L1/L2/L3 passed.
  source: README.md; docs/MILESTONES.md; docs/VERIFICATION.md; docs/QueueDesk_PRD.md §§2–4.

[INFO] Amount-limit difference resolved by PRD precedence
  Found: PaymentInstruction and README-linked exercises permit up to 1,000,000,000 minor units; PRD §11 proposes 10,000,000.
  Note: Plan Phase 1 to align the implementation, tests and implementation notes with the PRD range 1..10,000,000. Preserve the source PRD as supplied.
  source: src/main/java/dev/queuedesk/domain/PaymentInstruction.java; docs/MILESTONES.md; docs/QueueDesk_PRD.md §11.

[INFO] Duplicate scenario counts resolved by PRD precedence
  Found: The starter counts duplicate attempts inside --count and repeats every fifth submission; SC02 specifies 20 unique plus five repeats.
  Note: Phase 4 must make unique and attempt counts explicit and prove 25 attempts, 20 instructions and 20 admission events.
  source: README.md; tools/Generate.java; docs/QueueDesk_PRD.md §§13–14.

[INFO] Generator limits are a staged capability gap
  Found: The starter is sequential with a fixed 60-second sending cap; GN06 permits configurable work up to concurrency four and five minutes.
  Note: Preserve GN04 defaults (concurrency one, 60 seconds); implement configurable bounded work in Phase 4 without silently raising hard caps.
  source: README.md; tools/Generate.java; docs/QueueDesk_PRD.md §13.

[INFO] Error codes and delivery state need staged evolution
  Found: The starter emits INVALID_REQUEST / IDEMPOTENCY_CONFLICT / CAPACITY and NOT_CONFIGURED, with a restart-to-clear capacity message.
  Note: Plan stable reason/error codes and truthful durable capacity guidance in Phase 1; introduce delivery evidence in Phases 2–3. Never clear durable work to recover capacity.
  source: src/main/java/dev/queuedesk/http/ApiErrors.java; src/main/java/dev/queuedesk/http/InstructionController.java; docs/QueueDesk_PRD.md §§6–8, §10.

[INFO] Visuals and PDF describe the target
  Found: Seven diagrams depict Postgres, outbox, worker, quarantine and Angular that are not in the starter.
  Note: Read all seven source PNGs directly. Extracted the 24-page PDF for cross-checking named requirements/scenarios. Markdown, PDF, ZIP and all diagrams match docs/SOURCES.json byte sizes and SHA-256 values. Treat PDF/Markdown as one specification, not duplicate scope.
  source: docs/QueueDesk_PRD.md; docs/QueueDesk_PRD.pdf; docs/images/; docs/SOURCES.json.

[INFO] Later work remains staged
  Found: PR11 is L4; PR12 and cloud/Kubernetes exercises are deferred, with separate design/entry conditions.
  Note: Phase 8 includes read-only Angular after the reliability gate. BACKLOG.md preserves controlled actions and optional deployment work without weakening PRD entry conditions.
  source: docs/QueueDesk_PRD.md §§4, 15, 20, 22.

[INFO] L5 fraud-risk scope was excluded from the initial plan
  Found: PRD §§23–25 define PR13–PR16, RS01–RS06, TG04 and Figure 8 (QueueDesk_Risk_Gate.png); §15 reserves the read-only risk panel for L5. None were in requirements, roadmap or backlog.
  Note: Preserved as BACKLOG.md B04 with mock-first/live-gated entry conditions. UI mockups 04-risk-review, 05-risk-assessment and the RS05 catalog entry are B04 design inputs, not Phase 8 scope.
  source: docs/QueueDesk_PRD.md §§15, 17A, 23–25; docs/images/QueueDesk_Risk_Gate.png; docs/images/ui/.

[INFO] Distributed tracing acceptance was only partially planned
  Found: PRD §17A requires trace context across outbox/MQ, spans per attempt, async links and exporter-failure behavior (TG02/TG03); OBS-01/OBS-02 covered logs/metrics only.
  Note: Added TEL-01/TEL-02 to Phase 7 with TG02/TG03 acceptance. Telemetry mockups 07/08 assume this plumbing exists.
  source: docs/QueueDesk_PRD.md §17A; docs/images/ui/07-telemetry.png; docs/images/ui/08-trace-detail.png.

[INFO] UI mockups arrived after the planning scaffold
  Found: Ten read-only dashboard mockups in docs/images/ui/ (added after intake) plus the previously unlisted QueueDesk_Risk_Gate.png. All figures are illustrative; no operator writes are shown.
  Note: Added to INGEST-MANIFEST.yaml. L4 screens (01–03, 06–10 except RS05/risk spans) are Phase 8 design inputs; risk screens are B04. SOURCES.json is unchanged: it pins library-sourced files and the ui/ files have no library provenance.
  source: docs/images/ui/; docs/images/QueueDesk_Risk_Gate.png.

[INFO] PRD §9 audit records were unowned until the pre-development audit
  Found: instruction_audit transition records with atomic commit had no owning requirement; the UI timeline and TG02 assume them.
  Note: Extended DATA-01 (audit table plus admission audit row) and WORK-01/STATE-01 (audit with outcome transitions). No new IDs; no renumbering.
  source: docs/QueueDesk_PRD.md §9; docs/images/ui/03-payment-detail.png.

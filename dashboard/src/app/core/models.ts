/**
 * API read models: TypeScript mirrors of the backend response records.
 *
 * Shapes follow the Java records exactly (InstructionController.View,
 * RunController.RunSummary, and friends). The dashboard never invents
 * fields the API did not send; optional fields (`riskStatus`, `traceId`)
 * exist so older backends that omit them still type-check. Amounts stay
 * integer minor units end-to-end and are formatted only at render time.
 */

/** The payment payload exactly as admitted (echoed back on every instruction read). */
export interface PaymentInstructionPayload {
  clientReference: string;
  runId: string;
  senderParticipant: string;
  receiverParticipant: string;
  amountMinor: number;
  currency: string;
}

/** Business lifecycle states. Delivery progress is a separate axis (see DeliveryMarker). */
export type BusinessState =
  | 'RECEIVED'
  | 'BUSINESS_ACCEPTED'
  | 'PENDING_CONFIRMATION'
  | 'COMPLETED_SIMULATED'
  | 'REJECTED'
  | 'RISK_HELD';

/** Derived delivery state (outbox/queue progress). May lag the business outcome. */
export type DeliveryMarker = 'PENDING_PUBLISH' | 'PUBLISHED' | 'RETRYING' | 'QUARANTINED';

/** One instruction as the API returns it: business state, delivery state, payload, latest risk verdict. */
export interface InstructionView {
  instructionId: string;
  status: BusinessState | string;
  reason: string | null;
  receivedAt: string;
  updatedAt: string;
  statusUrl: string;
  correlationId: string;
  delivery: DeliveryMarker | string;
  payload: PaymentInstructionPayload;
  // DASH-06: latest risk assessment joined onto the read; all null with the
  // gate OFF or before any assessment exists. Older backends omit them.
  riskStatus?: string | null;
  assessmentId?: string | null;
  riskReasonCodes?: string[] | null;
  // W3C trace id parsed from the instruction's outbox traceparent; null when
  // absent or malformed (older backends omit it). Detail views only.
  traceId?: string | null;
}

/**
 * One durable business transition from the audit trail, in commit order.
 * Business transitions only, never transport retries.
 */
export interface AuditEvent {
  oldState: string;
  newState: string;
  reason: string | null;
  actor: string;
  recordedAt: string;
}

/** Audit-timeline response for one instruction. */
export interface InstructionAudit {
  instructionId: string;
  items: AuditEvent[];
}

/**
 * One worker processing attempt in write order. Telemetry, never business
 * truth: a rolled-back attempt leaves no row.
 */
export interface AttemptView {
  eventId: string;
  result: string;
  reason: string | null;
  deliveryCount: number | null;
  startedAt: string;
  endedAt: string | null;
}

/** Attempt-evidence response for one instruction. */
export interface InstructionAttempts {
  instructionId: string;
  items: AttemptView[];
}

/** One cursor page of instructions (`nextCursor` null on the last page). */
export interface InstructionPage {
  items: InstructionView[];
  nextCursor: string | null;
}

/** Instruction-list query filters; every field except `limit` is optional. */
export interface ListFilters {
  runId?: string;
  status?: string;
  since?: string;
  until?: string;
  limit: number;
  cursor?: string;
  // Newest-first paging; omit for the ascending default. Cursors are
  // direction-scoped server-side, so a scope never mixes orders.
  order?: 'asc' | 'desc';
}

/**
 * Lab-wide overview census: windowed instruction counts plus current
 * outbox/reconciliation gauges (gauges are never windowed). `since`/`until`
 * echo the applied window, null when unbounded.
 */
export interface LabSummary {
  admitted: number;
  byState: Record<string, number>;
  outboxPending: number;
  outboxPublished: number;
  reconcileOpen: number;
  since: string | null;
  until: string | null;
}

/** One throughput bucket: completions counted from the bucket start instant. */
export interface ThroughputPoint {
  at: string;
  count: number;
}

/** Throughput series, zero-filled across [since, until] so the chart is contiguous. */
export interface ThroughputView {
  metric: string;
  bucketSeconds: number;
  points: ThroughputPoint[];
  since: string;
  until: string;
}

/** Newest lab-wide payments for the overview table. */
export interface RecentPayments {
  items: InstructionView[];
}

/** One run's accounting: admitted total, counts by state, and delivery gauges. */
export interface RunSummary {
  runId: string;
  admitted: number;
  byState: Record<string, number>;
  outboxPending: number;
  outboxPublished: number;
  reconcileOpen: number;
}

/** One quarantined event and why it is held. */
export interface HeldItem {
  eventId: string;
  instructionId: string;
  reason: string;
  firstSeenAt: string;
  lastSeenAt: string;
}

/** Quarantine projection plus freshness (`stale` when the heartbeat is old). */
export interface QuarantineView {
  lastReconciledAt: string | null;
  stale: boolean;
  lastError: string | null;
  items: HeldItem[];
}

/** Laboratory safeguard status: backlog/disk checks, combined halt, and reasons. */
export interface Guards {
  outboxPending: number;
  backlogLimit: number;
  backlogHalt: boolean;
  diskFreeBytes: number;
  diskReserveBytes: number;
  diskHalt: boolean;
  haltTraffic: boolean;
  reasons: string[];
}

/** One actuator health contributor (db, diskSpace, mq, ping, …). */
export interface HealthComponent {
  status: string;
  details?: Record<string, unknown>;
}

/** Actuator health response (whole app or one probe group). */
export interface Health {
  status: string;
  components?: Record<string, HealthComponent>;
}

/** One metric reading; `value` null means "no reading", never zero. */
export interface MetricValue {
  name: string;
  value: number | null;
}

/** Final business states that never change again. */
export const TERMINAL_STATES: ReadonlySet<string> = new Set(['COMPLETED_SIMULATED', 'REJECTED']);

/**
 * Queues-page snapshot. `mq.depthEstimate` is an application estimate from
 * durable tables, never a broker probe; `quarantine.count` is null while
 * the projection is stale (last observed value kept separately).
 */
export interface QueuesView {
  asOf: string;
  outbox: {
    pending: number;
    oldestPendingAgeSeconds: number;
    lastPublishedAt: string;
    dispatch: string;
    lastEventId: string;
  };
  mq: {
    connected: boolean;
    queueManager: string;
    depthEstimate: number;
    depthEstimateNote: string;
    oldestEstimateAgeSeconds: number;
    activeConsumers: number;
    consumerState: string;
    lastSendAt: string;
  };
  quarantine: {
    count: number | null;
    lastObserved: number;
    lastObservedAt: string;
    freshnessAgeSeconds: number;
    stale: boolean;
  };
  dedupLast15Min: number;
}

/**
 * Telemetry summary. `riskProvider` is null when the gate is off, and
 * `exporter.lastExportAt` is null until something has been exported.
 * Empty latency windows report p95 -1 with n 0.
 */
export interface TelemetrySummary {
  asOf: string;
  windowMinutes: number;
  admission: { p95Ms: number; n: number; note: string };
  completion: { p95Ms: number; n: number; note: string };
  riskProvider: { p95Ms: number; n: number; mode: string } | null;
  sampling: { percent: number; sampledInstructions: number };
  exporter: {
    status: string;
    lastExportAt: string | null;
    spansSent15Min: number;
    spansDropped: number;
    bufferedSpans: number;
  };
}

/** One per-minute latency point (p95 over observed samples, plus the sample count). */
export interface LatencyPoint {
  at: string;
  p95Ms: number;
  n: number;
}

/** Latency series for one metric. */
export interface LatencySeries {
  metric: string;
  bucketSeconds: number;
  points: LatencyPoint[];
}

/** One sampled trace row, joined to its instruction where possible. */
export interface TraceItem {
  instructionId: string;
  clientReference: string;
  traceId: string;
  outcome: string;
  elapsedMs: number;
}

/** Trace-list response. */
export interface TracePage {
  items: TraceItem[];
  nextCursor: string | null;
}

/** One retained log event from the backend's live ring (no stack traces). */
export interface LogEntry {
  at: string;
  level: string;
  logger: string;
  message: string;
}

/** Log-ring response with eviction accounting. */
export interface LogList {
  items: LogEntry[];
  dropped: number;
  capacity: number;
  note: string;
}

/**
 * One waterfall span. Names are the real `queuedesk.*` span names; offsets
 * are relative to trace start and durations are not additive.
 */
export interface TraceSpan {
  spanId: string;
  parentSpanId: string | null;
  name: string;
  startOffsetMs: number;
  durationMs: number;
  attributes: Record<string, string>;
}

/** A cross-boundary span link (reserved; currently always an empty list). */
export interface AsyncLink {
  fromSpanId: string;
  toSpanId: string;
  via: string;
}

/** Trace detail: business context plus the full span waterfall. */
export interface TraceDetail {
  traceId: string;
  instructionId: string;
  clientReference: string;
  participants: string;
  sampledPercent: number;
  businessOutcome: string;
  riskDecision: string | null;
  riskMode: string;
  endToEndMs: number;
  admissionMs: number;
  spans: TraceSpan[];
  asyncLinks: AsyncLink[];
  auditUrl: string;
}

/**
 * One scenario catalog entry. `commandPreview` is pre-rendered by the
 * backend from the real CLI template; the copy button copies it verbatim
 * and never re-derives flags.
 */
export interface ScenarioItem {
  id: string;
  title: string;
  group: string;
  blurb: string;
  seed: number;
  unique: number;
  repeats: number;
  maxAttempts: number;
  riskMode: string;
  commandPreview: string;
  commandArgs: string[];
  dryRunDefault: boolean;
}

/** Scenario catalog response. */
export interface ScenarioCatalog {
  items: ScenarioItem[];
}

/** One discovered run (from stored instructions, not report files). */
export interface ScenarioRunItem {
  runId: string;
  scenarioId: string | null;
  scenarioTitle: string;
  startedAt: string;
  admitted: number;
  summary: string;
}

/** Run-history page (offset cursor in `nextCursor`). */
export interface ScenarioRunPage {
  items: ScenarioRunItem[];
  nextCursor: string | null;
}

/** One outcome line with its pass/fail verdict. */
export interface RunReportAssertion {
  text: string;
  passed: boolean;
}

/**
 * Server-derived run evidence. Nullable counters (`httpAttempts`,
 * `repeatResponses`) mean "not persisted server-side" — unavailable, never
 * zero-masked.
 */
export interface ScenarioRunReport {
  runId: string;
  scenarioId: string | null;
  scenarioTitle: string | null;
  startedAt: string | null;
  seed: number | null;
  riskMode: string;
  httpAttempts: number | null;
  uniqueAdmitted: number;
  simulatedCompleted: number;
  unknownConfirmations: number;
  repeatResponses: number | null;
  duplicateOutcomes: number;
  assertions: RunReportAssertion[];
  generator: {
    scenario: string | null;
    seed: number | null;
    unique: number;
    repeats: number;
    maxAttempts: number;
    participants: string;
  };
  commandPreview: string;
  commandArgs: string[];
  dryRunDefault: boolean;
}

/** Every business state the filter dropdowns offer, in lifecycle order. */
export const KNOWN_STATES: readonly string[] = [
  'RECEIVED',
  'BUSINESS_ACCEPTED',
  'PENDING_CONFIRMATION',
  'COMPLETED_SIMULATED',
  'REJECTED',
  'RISK_HELD',
];

/**
 * Jev metered-usage ledger. All zeros unless live calls happened; amounts
 * elsewhere in the risk views stay integer minor units end-to-end.
 */
export interface RiskUsage {
  calls: number;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
  budgetUsd: number;
  exhausted: boolean;
}

/** Risk summary: gate mode, hold counters, and the usage ledger. */
export interface RiskSummary {
  asOf: string;
  mode: string;
  riskHeld: number;
  reviewRequired: number;
  notAssessed: number;
  note: string;
  usage: RiskUsage;
}

/** One held-list row: the payment, its business outcome, and why the gate held it. */
export interface RiskHeldItem {
  instructionId: string;
  clientReference: string;
  amountMinor: number;
  currency: string;
  businessOutcome: string;
  riskOutput: string;
  reasonCodes: string[];
}

/** Held-list page; `mode` rides along so an empty OFF-mode list explains itself. */
export interface RiskHeldPage {
  items: RiskHeldItem[];
  nextCursor: string | null;
  mode: string;
}

/** Held-list query filters; every field except `limit` is optional. */
export interface RiskFilters {
  assessment?: string;
  reason?: string;
  q?: string;
  limit: number;
  cursor?: string;
}

/** One rule that fired, with the feature evidence that tripped it. */
export interface RiskMatchedRule {
  code: string;
  detail: string;
}

/**
 * One provider answer (or abstention when `answer` is null). `confidence`
 * is confidence in the answer to the exact question — see `confidenceNote`,
 * always rendered with it; never a probability of fraud.
 */
export interface RiskSignal {
  questionId: string;
  question: string;
  type: string;
  answer: boolean | null;
  confidence: number | null;
  confidenceNote: string;
}

/** One timed stage of an assessment, for the detail timeline. */
export interface RiskStage {
  stage: string;
  durationMs: number | null;
}

/** The exact versions that produced an assessment. */
export interface RiskVersions {
  featureSchema: string;
  rules: string;
  policy: string;
  questions: string;
}

/** Full assessment detail: features seen, rules matched, signals, timing, versions. */
export interface RiskAssessmentDetail {
  instructionId: string;
  clientReference: string;
  participants: string;
  amountMinor: number;
  currency: string;
  businessOutcome: string;
  riskOutput: string;
  riskOutputText: string;
  features: Record<string, unknown>;
  matchedRules: RiskMatchedRule[];
  policy: string;
  signals: RiskSignal[];
  timeline: RiskStage[];
  totalMs: number | null;
  assessedAt: string;
  traceId: string | null;
  versions: RiskVersions;
}

/** Every policy verdict the risk filters offer. */
export const KNOWN_RISK_RESULTS: readonly string[] = ['ALLOW', 'REVIEW', 'NOT_ASSESSED', 'REJECT'];

/** Every predefined reason code the policy can emit (never model prose). */
export const KNOWN_REASON_CODES: readonly string[] = [
  'NEW_RECIPIENT',
  'UNUSUAL_AMOUNT',
  'SIMULATED_POLICY_BLOCK',
];

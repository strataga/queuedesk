// API read models. Shapes mirror the backend records exactly (InstructionController.View,
// RunController.RunSummary, QuarantineController.QuarantineView, OpsController.Guards);
// the dashboard never invents fields the API did not send.

export interface PaymentInstructionPayload {
  clientReference: string;
  runId: string;
  senderParticipant: string;
  receiverParticipant: string;
  amountMinor: number;
  currency: string;
}

export type BusinessState =
  | 'RECEIVED'
  | 'BUSINESS_ACCEPTED'
  | 'PENDING_CONFIRMATION'
  | 'COMPLETED_SIMULATED'
  | 'REJECTED'
  | 'RISK_HELD';

export type DeliveryMarker = 'PENDING_PUBLISH' | 'PUBLISHED' | 'RETRYING' | 'QUARANTINED';

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

// Durable business audit trail (mockup 03 Audit tab): instruction_audit rows in
// commit order — business transitions only, never transport retries.
export interface AuditEvent {
  oldState: string;
  newState: string;
  reason: string | null;
  actor: string;
  recordedAt: string;
}

export interface InstructionAudit {
  instructionId: string;
  items: AuditEvent[];
}

// Transport attempt evidence (mockup 03 transport section): processing_attempt
// rows in write order. Telemetry, never business truth.
export interface AttemptView {
  eventId: string;
  result: string;
  reason: string | null;
  deliveryCount: number | null;
  startedAt: string;
  endedAt: string | null;
}

export interface InstructionAttempts {
  instructionId: string;
  items: AttemptView[];
}

export interface InstructionPage {
  items: InstructionView[];
  nextCursor: string | null;
}

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

// Lab-wide overview census: windowed instruction counts plus current
// outbox/reconcile gauges (never windowed). since/until echo the applied
// window, null when unbounded.
export interface LabSummary {
  admitted: number;
  byState: Record<string, number>;
  outboxPending: number;
  outboxPublished: number;
  reconcileOpen: number;
  since: string | null;
  until: string | null;
}

// Mockup 01 throughput graph: durable per-bucket completion counts,
// zero-filled across [since, until] so the chart is contiguous.
export interface ThroughputPoint {
  at: string;
  count: number;
}

export interface ThroughputView {
  metric: string;
  bucketSeconds: number;
  points: ThroughputPoint[];
  since: string;
  until: string;
}

export interface RecentPayments {
  items: InstructionView[];
}

export interface RunSummary {
  runId: string;
  admitted: number;
  byState: Record<string, number>;
  outboxPending: number;
  outboxPublished: number;
  reconcileOpen: number;
}

export interface HeldItem {
  eventId: string;
  instructionId: string;
  reason: string;
  firstSeenAt: string;
  lastSeenAt: string;
}

export interface QuarantineView {
  lastReconciledAt: string | null;
  stale: boolean;
  lastError: string | null;
  items: HeldItem[];
}

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

export interface HealthComponent {
  status: string;
  details?: Record<string, unknown>;
}

export interface Health {
  status: string;
  components?: Record<string, HealthComponent>;
}

export interface MetricValue {
  name: string;
  value: number | null;
}

export const TERMINAL_STATES: ReadonlySet<string> = new Set(['COMPLETED_SIMULATED', 'REJECTED']);

// §9.1 Queues page (mockup 06). mq.depthEstimate is an application estimate
// (never a broker probe); quarantine.count is null while the projection is stale.
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

// §9.2 Telemetry page (mockup 07). riskProvider is null and lastExportAt is null
// (no collector yet) when the risk gate is off / nothing has been exported.
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

export interface LatencyPoint {
  at: string;
  p95Ms: number;
  n: number;
}

export interface LatencySeries {
  metric: string;
  bucketSeconds: number;
  points: LatencyPoint[];
}

export interface TraceItem {
  instructionId: string;
  clientReference: string;
  traceId: string;
  outcome: string;
  elapsedMs: number;
}

export interface TracePage {
  items: TraceItem[];
  nextCursor: string | null;
}

// Live process log ring (mockup 07 Logs tab): newest last, no stack traces.
export interface LogEntry {
  at: string;
  level: string;
  logger: string;
  message: string;
}

export interface LogList {
  items: LogEntry[];
  dropped: number;
  capacity: number;
  note: string;
}

// §9.3 Trace detail (mockup 08). Span names are the real queuedesk.* names;
// offsets are relative to request start and durations are not additive.
export interface TraceSpan {
  spanId: string;
  parentSpanId: string | null;
  name: string;
  startOffsetMs: number;
  durationMs: number;
  attributes: Record<string, string>;
}

export interface AsyncLink {
  fromSpanId: string;
  toSpanId: string;
  via: string;
}

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

// §9.4 Scenarios + run detail (mockups 09/10). commandPreview is pre-rendered by
// the backend from the REAL CLI template; the UI copy button never re-derives flags.
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

export interface ScenarioCatalog {
  items: ScenarioItem[];
}

export interface ScenarioRunItem {
  runId: string;
  scenarioId: string | null;
  scenarioTitle: string;
  startedAt: string;
  admitted: number;
  summary: string;
}

export interface ScenarioRunPage {
  items: ScenarioRunItem[];
  nextCursor: string | null;
}

export interface RunReportAssertion {
  text: string;
  passed: boolean;
}

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

export const KNOWN_STATES: readonly string[] = [
  'RECEIVED',
  'BUSINESS_ACCEPTED',
  'PENDING_CONFIRMATION',
  'COMPLETED_SIMULATED',
  'REJECTED',
  'RISK_HELD',
];

// §9.5 Risk UI (mockups 04/05). Shapes mirror RiskController exactly: the held
// list body carries items only (the cursor rides the X-Next-Cursor header like
// the instruction list); amounts stay integer minor units end-to-end.
export interface RiskUsage {
  calls: number;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
  budgetUsd: number;
  exhausted: boolean;
}

export interface RiskSummary {
  asOf: string;
  mode: string;
  riskHeld: number;
  reviewRequired: number;
  notAssessed: number;
  note: string;
  usage: RiskUsage;
}

export interface RiskHeldItem {
  instructionId: string;
  clientReference: string;
  amountMinor: number;
  currency: string;
  businessOutcome: string;
  riskOutput: string;
  reasonCodes: string[];
}

export interface RiskHeldPage {
  items: RiskHeldItem[];
  nextCursor: string | null;
  mode: string;
}

export interface RiskFilters {
  assessment?: string;
  reason?: string;
  q?: string;
  limit: number;
  cursor?: string;
}

export interface RiskMatchedRule {
  code: string;
  detail: string;
}

export interface RiskSignal {
  questionId: string;
  question: string;
  type: string;
  answer: boolean | null;
  confidence: number | null;
  confidenceNote: string;
}

export interface RiskStage {
  stage: string;
  durationMs: number | null;
}

export interface RiskVersions {
  featureSchema: string;
  rules: string;
  policy: string;
  questions: string;
}

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

export const KNOWN_RISK_RESULTS: readonly string[] = ['ALLOW', 'REVIEW', 'NOT_ASSESSED', 'REJECT'];

export const KNOWN_REASON_CODES: readonly string[] = [
  'NEW_RECIPIENT',
  'UNUSUAL_AMOUNT',
  'SIMULATED_POLICY_BLOCK',
];

import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/overview/overview.page').then((m) => m.OverviewPage),
    title: 'Overview — QueueDesk lab',
  },
  {
    path: 'payments',
    loadComponent: () =>
      import('./pages/instruction-list/instruction-list.page').then((m) => m.InstructionListPage),
    title: 'Payments — QueueDesk lab',
  },
  {
    path: 'payments/:id',
    loadComponent: () =>
      import('./pages/instruction-detail/instruction-detail.page').then(
        (m) => m.InstructionDetailPage,
      ),
    title: 'Payment detail — QueueDesk lab',
  },
  {
    path: 'runs/:runId',
    loadComponent: () => import('./pages/run-detail/run-detail.page').then((m) => m.RunDetailPage),
    title: 'Run detail — QueueDesk lab',
  },
  {
    path: 'ops',
    loadComponent: () => import('./pages/ops/ops.page').then((m) => m.OpsPage),
    title: 'Operations — QueueDesk lab',
  },
  {
    path: 'risk',
    loadComponent: () =>
      import('./pages/risk-review/risk-review.page').then((m) => m.RiskReviewPage),
    title: 'Risk review — QueueDesk lab',
  },
  {
    path: 'risk/assessments/:instructionId',
    loadComponent: () =>
      import('./pages/risk-assessment/risk-assessment.page').then((m) => m.RiskAssessmentPage),
    title: 'Risk assessment — QueueDesk lab',
  },
  {
    path: 'queues',
    loadComponent: () => import('./pages/queues/queues.page').then((m) => m.QueuesPage),
    title: 'Queues — QueueDesk lab',
  },
  {
    path: 'telemetry',
    loadComponent: () => import('./pages/telemetry/telemetry.page').then((m) => m.TelemetryPage),
    title: 'Telemetry — QueueDesk lab',
  },
  {
    path: 'telemetry/traces/:traceId',
    loadComponent: () =>
      import('./pages/trace-detail/trace-detail.page').then((m) => m.TraceDetailPage),
    title: 'Trace detail — QueueDesk lab',
  },
  {
    path: 'scenarios',
    loadComponent: () => import('./pages/scenarios/scenarios.page').then((m) => m.ScenariosPage),
    title: 'Scenarios — QueueDesk lab',
  },
  {
    path: 'scenarios/runs/:runId',
    loadComponent: () =>
      import('./pages/scenario-run-detail/scenario-run-detail.page').then(
        (m) => m.ScenarioRunDetailPage,
      ),
    title: 'Scenario run — QueueDesk lab',
  },
  // Pre-rename Payments URLs keep working (query params preserved).
  { path: 'instructions', redirectTo: 'payments', pathMatch: 'full' },
  { path: 'instructions/:id', redirectTo: 'payments/:id' },
  { path: '**', redirectTo: '' },
];

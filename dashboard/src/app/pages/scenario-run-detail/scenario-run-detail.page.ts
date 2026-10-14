import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Observable, map, switchMap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { ScenarioRunReport } from '../../core/models';
import { PollResult, PollingService } from '../../core/polling.service';
import { toView } from '../../core/poll-view';
import { RefreshNoteComponent } from '../../shared/refresh-note.component';
import { StateMessageComponent } from '../../shared/state-message.component';

// Scenario run detail (mockup 10): run report from GET /api/v1/scenarios/runs/{id}.
// Unknown run ids report zeros, never 404 (RunController convention). The
// reproducibility preview and copy button use the backend's REAL CLI template
// verbatim; Export report re-serves this JSON with an exportedAt stamp.
@Component({
  selector: 'qd-scenario-run-detail',
  imports: [CommonModule, RouterLink, RefreshNoteComponent, StateMessageComponent],
  templateUrl: './scenario-run-detail.page.html',
})
export class ScenarioRunDetailPage {
  private readonly api = inject(ApiService);
  private readonly polling = inject(PollingService);
  private readonly route = inject(ActivatedRoute);

  protected readonly runId$: Observable<string> = this.route.paramMap.pipe(
    map((params) => params.get('runId') ?? ''),
  );
  protected readonly view$: Observable<PollResult<ScenarioRunReport>> = this.runId$.pipe(
    switchMap((runId) => this.polling.poll(() => this.api.getScenarioRun(runId))),
  );

  protected copied = false;

  protected describe = (error: unknown): string => this.polling.describeError(error);
  protected readonly toView = toView;

  protected barWidth(part: number | null, total: number | null): string {
    if (part == null || total == null || total <= 0) return '0%';
    return `${Math.min(100, (part / total) * 100)}%`;
  }

  protected copyCommand(preview: string): void {
    try {
      const clipboard = navigator.clipboard;
      if (clipboard) {
        void clipboard.writeText(preview).then(
          () => {
            this.copied = true;
          },
          () => {
            this.copied = false;
          },
        );
        return;
      }
    } catch {
      // Clipboard unavailable: fall through to a non-copied state.
    }
    this.copied = false;
  }

  protected exportReport(report: ScenarioRunReport): void {
    const payload = JSON.stringify({ ...report, exportedAt: new Date().toISOString() }, null, 2);
    const url = URL.createObjectURL(new Blob([payload], { type: 'application/json' }));
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `${report.runId}-report.json`;
    anchor.click();
    URL.revokeObjectURL(url);
  }
}

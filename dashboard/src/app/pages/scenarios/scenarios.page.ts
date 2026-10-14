import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable, forkJoin } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { ScenarioCatalog, ScenarioItem, ScenarioRunPage } from '../../core/models';
import { PollResult, PollingService } from '../../core/polling.service';
import { toView } from '../../core/poll-view';
import { TimeAgoPipe } from '../../shared/time-ago.pipe';
import { RefreshNoteComponent } from '../../shared/refresh-note.component';
import { StateMessageComponent } from '../../shared/state-message.component';

interface ScenariosSnapshot {
  catalog: ScenarioCatalog;
  runs: ScenarioRunPage;
}

/**
 * Scenarios page: the failure-drill catalog plus recent runs discovered
 * from durable state. The UI executes nothing. Command previews render
 * verbatim from the backend's real CLI template; the copy button copies
 * that string and never re-derives flags.
 */
@Component({
  selector: 'qd-scenarios',
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    TimeAgoPipe,
    RefreshNoteComponent,
    StateMessageComponent,
  ],
  templateUrl: './scenarios.page.html',
})
export class ScenariosPage {
  private readonly api = inject(ApiService);
  private readonly polling = inject(PollingService);

  protected query = '';
  protected selectedId: string | null = null;
  protected copied = false;

  protected readonly view$: Observable<PollResult<ScenariosSnapshot>> = this.polling.poll(() =>
    forkJoin({
      catalog: this.api.listScenarios(),
      runs: this.api.listScenarioRuns(20),
    }),
  );

  protected describe = (error: unknown): string => this.polling.describeError(error);
  protected readonly toView = toView;

  /** Client-side catalog search over ID, title, blurb, and group. */
  protected filtered(items: ScenarioItem[]): ScenarioItem[] {
    const q = this.query.trim().toLowerCase();
    if (q === '') return items;
    return items.filter((s) =>
      `${s.id} ${s.title} ${s.blurb} ${s.group}`.toLowerCase().includes(q),
    );
  }

  protected selected(items: ScenarioItem[]): ScenarioItem | null {
    if (items.length === 0) return null;
    return items.find((s) => s.id === this.selectedId) ?? items[0];
  }

  protected select(id: string): void {
    this.selectedId = id;
    this.copied = false;
  }

  /** Copies the backend-rendered command verbatim; flags are never re-derived here. */
  protected copyCommand(preview: string): void {
    const done = () => {
      this.copied = true;
    };
    try {
      const clipboard = navigator.clipboard;
      if (clipboard) {
        void clipboard.writeText(preview).then(done, () => {
          this.copied = false;
        });
        return;
      }
    } catch {
      // Clipboard unavailable (permissions, insecure context): fall through.
    }
    this.copied = false;
  }
}

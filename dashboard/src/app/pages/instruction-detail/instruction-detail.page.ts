import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Observable, map, switchMap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { InstructionAudit, InstructionAttempts, InstructionView } from '../../core/models';
import { PollResult, PollingService } from '../../core/polling.service';
import { toView } from '../../core/poll-view';
import { MinorCurrencyPipe } from '../../shared/minor-currency.pipe';
import { TimeAgoPipe } from '../../shared/time-ago.pipe';
import { RefreshNoteComponent } from '../../shared/refresh-note.component';
import { StateMessageComponent } from '../../shared/state-message.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

export type DetailTab = 'timeline' | 'request' | 'audit';

// Instruction detail (UI-03, mockup 03): stat cards, Timeline/Request/Audit tabs,
// transport events, a reconciliation panel for unknown confirmations, and
// copyable identifiers. The audit trail and transport attempts ride their own
// polls: a backend without those sub-resources degrades those panels alone,
// never the admission + current-state core. A lagging delivery marker can never
// overwrite the completed business outcome: the two render in separate sections.
@Component({
  selector: 'qd-instruction-detail',
  imports: [
    CommonModule,
    RouterLink,
    MinorCurrencyPipe,
    TimeAgoPipe,
    RefreshNoteComponent,
    StateMessageComponent,
    StatusBadgeComponent,
  ],
  templateUrl: './instruction-detail.page.html',
})
export class InstructionDetailPage {
  private readonly api = inject(ApiService);
  private readonly polling = inject(PollingService);
  private readonly route = inject(ActivatedRoute);

  protected tab: DetailTab = 'timeline';
  protected copied: string | null = null;

  // paramMap observable, not a snapshot read: the router reuses this component
  // across /instructions/A -> /instructions/B navigation.
  protected readonly id$: Observable<string> = this.route.paramMap.pipe(
    map((params) => params.get('id') ?? ''),
  );
  protected readonly view$: Observable<PollResult<InstructionView>> = this.id$.pipe(
    switchMap((id) => this.polling.poll(() => this.api.getInstruction(id))),
  );
  protected readonly audit$: Observable<PollResult<InstructionAudit>> = this.id$.pipe(
    switchMap((id) => this.polling.poll(() => this.api.getAudit(id))),
  );
  protected readonly attempts$: Observable<PollResult<InstructionAttempts>> = this.id$.pipe(
    switchMap((id) => this.polling.poll(() => this.api.getAttempts(id))),
  );

  protected describe = (error: unknown): string => this.polling.describeError(error);
  protected readonly toView = toView;

  protected selectTab(tab: DetailTab): void {
    this.tab = tab;
  }

  protected isUnknownId(error: unknown): boolean {
    return (
      error !== null && typeof error === 'object' && (error as { status?: unknown }).status === 404
    );
  }

  protected changedSinceAdmission(item: InstructionView): boolean {
    return item.updatedAt !== item.receivedAt;
  }

  protected copyText(value: string, which: string): void {
    try {
      const clipboard = navigator.clipboard;
      if (clipboard) {
        void clipboard.writeText(value).then(
          () => {
            this.copied = which;
          },
          () => {
            this.copied = null;
          },
        );
        return;
      }
    } catch {
      // Clipboard unavailable (permissions, insecure context): no feedback.
    }
    this.copied = null;
  }
}

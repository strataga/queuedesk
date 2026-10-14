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

/**
 * Instruction detail: stat cards, Timeline/Request/Audit tabs, transport
 * attempt events, a reconciliation panel for unknown confirmations, and
 * copyable identifiers. Audit and attempts ride their own polls, so a
 * backend without those sub-resources degrades those panels alone, never
 * the admission-and-current-state core. Business outcome and delivery
 * marker render in separate sections: a lagging delivery marker can never
 * overwrite a completed business result.
 */
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

  /** Switches the visible detail tab (timeline, request, or audit). */
  protected selectTab(tab: DetailTab): void {
    this.tab = tab;
  }

  /** True when the failure is a genuine 404 (render "no such payment", not an outage banner). */
  protected isUnknownId(error: unknown): boolean {
    return (
      error !== null && typeof error === 'object' && (error as { status?: unknown }).status === 404
    );
  }

  /** Whether the instruction has transitioned at least once since admission. */
  protected changedSinceAdmission(item: InstructionView): boolean {
    return item.updatedAt !== item.receivedAt;
  }

  /** Copies an identifier and marks which control succeeded; failure gives no false feedback. */
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

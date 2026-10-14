import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { FormsModule } from '@angular/forms';

/**
 * Application shell: header, primary navigation, the run-lookup box, and
 * the router outlet every page renders into. Deliberately thin — all data
 * work happens in the routed pages.
 */
@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive, FormsModule],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  private readonly router = inject(Router);
  /** Header run-lookup input, two-way bound to the template. */
  protected runLookup = '';

  /** Navigates to the typed run's detail page; blank input does nothing. */
  protected openRun(): void {
    const runId = this.runLookup.trim();
    if (runId.length > 0) void this.router.navigate(['/runs', runId]);
  }
}

import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { FormsModule } from '@angular/forms';

@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive, FormsModule],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  private readonly router = inject(Router);
  protected runLookup = '';

  protected openRun(): void {
    const runId = this.runLookup.trim();
    if (runId.length > 0) void this.router.navigate(['/runs', runId]);
  }
}

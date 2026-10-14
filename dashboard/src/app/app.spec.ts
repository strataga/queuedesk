/**
 * App shell smoke tests: the root component creates, and the simulation
 * banner plus read-only note are always visible (the lab must never look
 * like a production console).
 */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([])],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('should always show the simulation banner and read-only note', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('[role="note"]')?.textContent).toContain('SIMULATION');
    expect(compiled.textContent).toContain('Read-only');
  });
});

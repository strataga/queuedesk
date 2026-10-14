/**
 * Throughput chart: area rendering with max/time labels, a flat zero line
 * instead of a collapsed scale, and guidance when buckets are too few.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ThroughputChartComponent } from './throughput-chart.component';

describe('ThroughputChartComponent', () => {
  let fixture: ComponentFixture<ThroughputChartComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ThroughputChartComponent],
    }).compileComponents();
    fixture = TestBed.createComponent(ThroughputChartComponent);
  });

  function render(points: { at: string; count: number }[]): HTMLElement {
    fixture.componentInstance.points = points;
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders an area chart with max and time labels', () => {
    const el = render([
      { at: '2026-10-06T10:00:00Z', count: 40 },
      { at: '2026-10-06T10:01:00Z', count: 120 },
      { at: '2026-10-06T10:02:00Z', count: 80 },
    ]);
    expect(el.querySelector('svg')).not.toBeNull();
    expect(el.querySelector('polygon')).not.toBeNull();
    expect(el.querySelector('polyline')).not.toBeNull();
    const text = el.textContent ?? '';
    expect(text).toContain('120');
    expect(text).toContain('10:00');
    expect(text).toContain('10:02');
    expect(text).toContain('Latest bucket: 80');
  });

  it('renders a flat zero line instead of collapsing the scale', () => {
    const el = render([
      { at: '2026-10-06T10:00:00Z', count: 0 },
      { at: '2026-10-06T10:01:00Z', count: 0 },
    ]);
    expect(el.querySelector('svg')).not.toBeNull();
    expect(el.textContent).toContain('Latest bucket: 0');
  });

  it('asks for a wider window when buckets are too few', () => {
    const el = render([{ at: '2026-10-06T10:00:00Z', count: 3 }]);
    expect(el.querySelector('svg')).toBeNull();
    expect(el.textContent).toContain('at least two buckets');
  });
});

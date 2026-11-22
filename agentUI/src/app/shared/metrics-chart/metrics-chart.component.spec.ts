import { TestBed } from '@angular/core/testing';
import { MetricRangeResponse } from '../../core/api/api.models';
import { MetricsChartComponent } from './metrics-chart.component';

const SAMPLE_METRICS: MetricRangeResponse = {
  environmentId: 'local',
  service: 'checkout-api',
  metric: 'latency_ms',
  unit: 'ms',
  from: '2026-11-22T11:00:00Z',
  to: '2026-11-22T12:00:00Z',
  requestedStepSeconds: 60,
  effectiveStepSeconds: 60,
  downsampled: false,
  dataSource: 'prometheus',
  series: [
    {
      label: 'p95 latency',
      dimensions: { service: 'checkout-api' },
      points: [
        { timestamp: '2026-11-22T11:58:00Z', value: 120 },
        { timestamp: '2026-11-22T11:59:00Z', value: 240 },
      ],
    },
  ],
};

describe('MetricsChartComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MetricsChartComponent],
    }).compileComponents();
  });

  it('renders chart and accessible summary table and cleans up on destroy', () => {
    const fixture = TestBed.createComponent(MetricsChartComponent);
    fixture.componentRef.setInput('data', SAMPLE_METRICS);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('canvas')).not.toBeNull();
    const table = el.querySelector('table[aria-label="Metric data summary table"]');
    expect(table).not.toBeNull();
    expect(table?.textContent).toContain('p95 latency');
    expect(table?.textContent).toContain('240');

    expect(() => fixture.destroy()).not.toThrow();
  });
});

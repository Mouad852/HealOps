import { TestBed } from '@angular/core/testing';
import { TopologyResponse } from '../../core/api/api.models';
import { ServiceMapComponent } from './service-map.component';

const SAMPLE_TOPOLOGY: TopologyResponse = {
  environmentId: 'local',
  from: '2026-11-22T11:00:00Z',
  to: '2026-11-22T12:00:00Z',
  dataSource: 'jaeger',
  nodes: [
    {
      id: 'api-gateway',
      service: 'api-gateway',
      environmentId: 'local',
      healthStatus: 'HEALTHY',
      activeIncidentCount: 0,
      highestSeverity: null,
      spanCount: 40,
      errorRatePercent: 0,
    },
    {
      id: 'checkout-api',
      service: 'checkout-api',
      environmentId: 'local',
      healthStatus: 'DEGRADED',
      activeIncidentCount: 1,
      highestSeverity: 'HIGH',
      spanCount: 25,
      errorRatePercent: 12.5,
    },
  ],
  edges: [
    {
      sourceService: 'api-gateway',
      targetService: 'checkout-api',
      callCount: 25,
      errorCount: 3,
      avgLatencyMs: 185,
    },
  ],
};

describe('ServiceMapComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ServiceMapComponent],
    }).compileComponents();
  });

  it('renders graph container, keyboard-accessible dependency tables, and emits service selection', () => {
    const fixture = TestBed.createComponent(ServiceMapComponent);
    fixture.componentRef.setInput('topology', SAMPLE_TOPOLOGY);

    const selected: string[] = [];
    fixture.componentInstance.serviceSelected.subscribe((svc) => {
      selected.push(svc);
    });

    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.service-map-canvas')).not.toBeNull();
    expect(el.querySelector('table[aria-label="Service nodes table"]')?.textContent).toContain('checkout-api');
    expect(el.querySelector('table[aria-label="Service dependency edges table"]')?.textContent).toContain('api-gateway');

    const svcButton = Array.from(el.querySelectorAll('table button')).find((b) =>
      b.textContent?.includes('checkout-api')
    ) as HTMLButtonElement | undefined;
    expect(svcButton).toBeTruthy();
    svcButton?.click();

    expect(selected).toEqual(['checkout-api']);
    expect(() => fixture.destroy()).not.toThrow();
  });
});

import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { apiInterceptor } from '../../core/api/api.interceptor';
import { EVENT_SOURCE_FACTORY } from '../../core/events/event-source.token';
import { createFakeEventSourceController } from '../../testing/fake-event-source';
import { IncidentDetailComponent } from './incident-detail.component';

describe('IncidentDetailComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    const sseController = createFakeEventSourceController();

    await TestBed.configureTestingModule({
      imports: [IncidentDetailComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
        { provide: EVENT_SOURCE_FACTORY, useValue: sseController.factory },
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('renders report markdown as formatted sanitized HTML without scripts', async () => {
    const fixture = TestBed.createComponent(IncidentDetailComponent);
    fixture.componentRef.setInput('incidentId', 'inc-closed-1');
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/incidents/inc-closed-1').flush({
      id: 'inc-closed-1',
      source: 'manual-demo',
      externalAlertId: null,
      fingerprint: 'fp-1',
      environmentId: 'demo',
      severity: 'HIGH',
      service: 'demo-workload',
      status: 'CLOSED',
      startedAt: '2026-11-22T12:00:00Z',
      createdAt: '2026-11-22T12:00:00Z',
      version: 5,
      labels: {},
      annotations: {},
      traceId: null,
    });
    await Promise.resolve();

    httpMock.expectOne('/api/v1/incidents/inc-closed-1/timeline').flush([]);
    httpMock.expectOne('/api/v1/incidents/inc-closed-1/evidence').flush([]);
    httpMock.expectOne('/api/v1/incidents/inc-closed-1/diagnoses').flush([]);
    httpMock.expectOne('/api/v1/incidents/inc-closed-1/proposed-actions').flush([]);
    httpMock.expectOne('/api/v1/incidents/inc-closed-1/health-comparisons').flush({
      incidentId: 'inc-closed-1',
      before: null,
      after: null,
      postRollback: null,
      snapshots: [],
    });
    httpMock.expectOne('/api/v1/tool-executions?incidentId=inc-closed-1').flush([]);
    httpMock.expectOne('/api/v1/runbooks/suggestions?incidentId=inc-closed-1').flush([]);

    await fixture.whenStable();

    httpMock.expectOne('/api/v1/incidents/inc-closed-1/report').flush({
      id: 'rep-1',
      incidentId: 'inc-closed-1',
      finalOutcome: 'CLOSED',
      generatedAt: '2026-11-22T12:05:00Z',
      structuredReport: { outcome: 'CLOSED' },
      renderedMarkdown: '# Incident Report\n\n**Outcome**: `CLOSED`<script>alert(1)</script>',
    });

    await Promise.resolve();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.report()).not.toBeNull();
    const mdContainer = (fixture.nativeElement as HTMLElement).querySelector('.healops-markdown');
    expect(mdContainer).not.toBeNull();
    expect(mdContainer?.querySelector('h1')?.textContent).toContain('Incident Report');
    expect(mdContainer?.querySelector('strong')?.textContent).toBe('Outcome');
    expect(mdContainer?.querySelector('script')).toBeNull();
    expect(mdContainer?.innerHTML).not.toContain('<script');
  });
});

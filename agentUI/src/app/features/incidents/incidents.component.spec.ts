import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { apiInterceptor } from '../../core/api/api.interceptor';
import { IncidentResponse } from '../../core/api/api.models';
import { DashboardEventsService } from '../../core/events/dashboard-events.service';
import { EVENT_SOURCE_FACTORY } from '../../core/events/event-source.token';
import { createFakeEventSourceController } from '../../testing/fake-event-source';
import { IncidentsComponent } from './incidents.component';

function makeIncident(id: string, service: string): IncidentResponse {
  return {
    id,
    source: 'prometheus',
    externalAlertId: `ext-${id}`,
    fingerprint: `fp-${id}`,
    environmentId: 'local',
    severity: 'HIGH',
    service,
    status: 'AWAITING_APPROVAL',
    startedAt: '2026-11-22T12:00:00Z',
    createdAt: '2026-11-22T12:00:00Z',
    version: 2,
    labels: { service },
    annotations: {},
    traceId: null,
  };
}

describe('IncidentsComponent', () => {
  let httpMock: HttpTestingController;
  let sseController: ReturnType<typeof createFakeEventSourceController>;
  let sseService: DashboardEventsService;

  beforeEach(async () => {
    localStorage.removeItem('healops.selectedEnvironmentId');
    sseController = createFakeEventSourceController();

    await TestBed.configureTestingModule({
      imports: [IncidentsComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
        { provide: EVENT_SOURCE_FACTORY, useValue: sseController.factory },
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    sseService = TestBed.inject(DashboardEventsService);
    sseService.connect('local');
    sseController.latest()?.emitOpen();
  });

  afterEach(() => {
    sseService.ngOnDestroy();
    httpMock.verify();
  });

  it('supports cursor pagination with Load more and empty/error states', async () => {
    const fixture = TestBed.createComponent(IncidentsComponent);
    fixture.detectChanges();

    httpMock
      .expectOne('/api/v1/incidents/page?environmentId=local&limit=20')
      .flush({ items: [makeIncident('inc-1', 'checkout-api')], nextCursor: 'cursor-page-2' });

    await fixture.whenStable();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.incidents().length).toBe(1);
    expect(comp.nextCursor()).toBe('cursor-page-2');

    comp.loadMore();
    httpMock
      .expectOne('/api/v1/incidents/page?environmentId=local&cursor=cursor-page-2&limit=20')
      .flush({ items: [makeIncident('inc-2', 'payments-api')], nextCursor: null });

    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp.incidents().length).toBe(2);
    expect(comp.nextCursor()).toBeNull();

    // SSE incident.updated refreshes page 1 without wiping out page 2 items or resetting nextCursor
    sseController.latest()?.emitNamedEvent('incident.updated', {
      schemaVersion: 'v1',
      id: 'ev-inc-1',
      sequenceNumber: 21,
      type: 'incident.updated',
      occurredAt: '2026-11-22T12:02:00Z',
      environmentId: 'local',
      incidentId: 'inc-1',
      traceId: null,
      entityVersion: 3,
      payload: { status: 'CLOSED' },
    });

    httpMock
      .expectOne('/api/v1/incidents/page?environmentId=local&limit=20')
      .flush({
        items: [{ ...makeIncident('inc-1', 'checkout-api'), status: 'CLOSED', version: 3 }],
        nextCursor: 'cursor-page-2',
      });

    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp.incidents().map((i) => i.id)).toEqual(['inc-1', 'inc-2']);
    expect(comp.incidents()[0].status).toBe('CLOSED');
    expect(comp.nextCursor()).toBeNull();
  });

  it('displays error message when page request fails', async () => {
    const fixture = TestBed.createComponent(IncidentsComponent);
    fixture.detectChanges();

    httpMock
      .expectOne('/api/v1/incidents/page?environmentId=local&limit=20')
      .flush(
        { type: 'about:blank', title: 'Server error', status: 500, detail: 'Backend unavailable' },
        { status: 500, statusText: 'Internal Server Error' }
      );

    await fixture.whenStable();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Backend unavailable');
  });
});

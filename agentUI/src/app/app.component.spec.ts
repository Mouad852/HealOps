import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app.component';
import { apiInterceptor } from './core/api/api.interceptor';
import { EVENT_SOURCE_FACTORY } from './core/events/event-source.token';
import { createFakeEventSourceController, FakeEventSourceController } from './testing/fake-event-source';

describe('AppComponent', () => {
  let httpMock: HttpTestingController;
  let sseController: FakeEventSourceController;

  beforeEach(async () => {
    localStorage.removeItem('healops.selectedEnvironmentId');
    sseController = createFakeEventSourceController();

    await TestBed.configureTestingModule({
      imports: [AppComponent],
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

  it('should bootstrap auth, environments, overview, and one shared SSE stream', async () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/auth/me').flush({
      username: 'operator',
      authorities: ['ROLE_OPERATOR', 'incident:read', 'incident:approve', 'env:local', 'env:staging'],
    });
    httpMock.expectOne('/api/v1/environments').flush([
      {
        id: 'local',
        name: 'local',
        kind: 'docker',
        displayLabel: 'Local Docker',
        enabled: true,
        capabilities: {},
        createdAt: '2026-11-22T12:00:00Z',
      },
      {
        id: 'staging',
        name: 'staging',
        kind: 'kubernetes',
        displayLabel: 'Staging Cluster',
        enabled: true,
        capabilities: {},
        createdAt: '2026-11-22T12:00:00Z',
      },
    ]);

    await fixture.whenStable();
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/overview?environmentId=local').flush({
      environmentId: 'local',
      environments: [],
      incidents: { totalCount: 2, activeCount: 1, byStatus: { AWAITING_APPROVAL: 1 }, bySeverity: { HIGH: 1 } },
      pendingApprovalCount: 1,
      serviceHealth: [],
      remediationAnalytics: {
        environmentId: 'local',
        authorizedEnvironments: ['local'],
        totalOutcomes: 0,
        successfulOutcomes: 0,
        failedOutcomes: 0,
        overallSuccessRate: 0,
        rollbackCount: 0,
        rollbackRate: 0,
        rollbackSuccessRate: null,
        meanApprovalDelayMs: null,
        meanTimeToRecoveryMs: null,
        byActionType: [],
        byService: [],
      },
      generatedAt: '2026-11-22T12:00:00Z',
    });

    await fixture.whenStable();
    fixture.detectChanges();

    expect(sseController.instances.length).toBe(1);
    expect(sseController.latest()?.url).toBe('/api/v1/events?environmentId=local');
    sseController.latest()?.emitOpen();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('HealOps Autopilot');
    expect(text).toContain('operator');
    expect(text).toContain('Operator');
    expect(text).toContain('Stream: connected');
    expect((fixture.nativeElement as HTMLElement).querySelector('[aria-live="polite"]')).not.toBeNull();
  });
});

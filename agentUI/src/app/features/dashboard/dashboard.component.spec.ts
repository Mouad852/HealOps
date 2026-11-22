import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { apiInterceptor } from '../../core/api/api.interceptor';
import { EVENT_SOURCE_FACTORY } from '../../core/events/event-source.token';
import { AuthStore } from '../../core/state/auth.store';
import { EnvironmentStore } from '../../core/state/environment.store';
import { createFakeEventSourceController } from '../../testing/fake-event-source';
import { DashboardComponent } from './dashboard.component';

describe('DashboardComponent', () => {
  let httpMock: HttpTestingController;
  let authStore: AuthStore;
  let envStore: EnvironmentStore;

  beforeEach(async () => {
    localStorage.removeItem('healops.selectedEnvironmentId');
    const sseController = createFakeEventSourceController();

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
        { provide: EVENT_SOURCE_FACTORY, useValue: sseController.factory },
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    authStore = TestBed.inject(AuthStore);
    envStore = TestBed.inject(EnvironmentStore);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('attaches runtime=docker when container label is set and populates demo-workload preset in demo env', async () => {
    authStore.setUserForTesting({
      username: 'operator',
      authorities: ['ROLE_OPERATOR', 'incident:read', 'incident:write', 'env:demo'],
    });
    envStore.selectEnvironment('demo');

    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.applyDemoWorkloadPreset();
    expect(comp.alertService()).toBe('demo-workload');
    expect(comp.alertContainer()).toBe('healops-demo-workload');

    const submitPromise = comp.submitDemoAlert();
    const createReq = httpMock.expectOne('/api/v1/incidents');
    expect(createReq.request.method).toBe('POST');
    const body = createReq.request.body as {
      environmentId: string;
      service: string;
      labels: Record<string, string>;
    };
    expect(body.environmentId).toBe('demo');
    expect(body.service).toBe('demo-workload');
    expect(body.labels['container']).toBe('healops-demo-workload');
    expect(body.labels['runtime']).toBe('docker');

    createReq.flush({
      id: 'inc-demo-1',
      source: 'manual-demo',
      externalAlertId: null,
      fingerprint: 'fp-1',
      environmentId: 'demo',
      severity: 'HIGH',
      service: 'demo-workload',
      status: 'ALERTED',
      startedAt: '2026-11-22T12:00:00Z',
      createdAt: '2026-11-22T12:00:00Z',
      version: 0,
      labels: body.labels,
      annotations: {},
      traceId: null,
    });

    await Promise.resolve();
    httpMock.expectOne('/api/v1/overview?environmentId=demo').flush({
      environmentId: 'demo',
      environments: [],
      incidents: { totalCount: 1, activeCount: 1, byStatus: { ALERTED: 1 }, bySeverity: { HIGH: 1 } },
      pendingApprovalCount: 0,
      serviceHealth: [],
      remediationAnalytics: {
        environmentId: 'demo',
        authorizedEnvironments: ['demo'],
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
      generatedAt: '2026-11-22T12:00:01Z',
    });

    await submitPromise;
    expect(comp.alertCreatedId()).toBe('inc-demo-1');
  });
});

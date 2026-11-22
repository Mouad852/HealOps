import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { apiInterceptor } from '../api/api.interceptor';
import { EVENT_SOURCE_FACTORY } from '../events/event-source.token';
import {
  createFakeEventSourceController,
  FakeEventSourceController,
} from '../../testing/fake-event-source';
import { DashboardStore } from './dashboard.store';
import { EnvironmentStore } from './environment.store';

function mockOverview(envId: string, activeCount: number) {
  return {
    environmentId: envId,
    environments: [],
    incidents: { totalCount: activeCount, activeCount, byStatus: {}, bySeverity: {} },
    pendingApprovalCount: activeCount,
    serviceHealth: [],
    remediationAnalytics: {
      environmentId: envId,
      authorizedEnvironments: [envId],
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
  };
}

describe('DashboardStore & EnvironmentStore', () => {
  let httpMock: HttpTestingController;
  let sseController: FakeEventSourceController;
  let envStore: EnvironmentStore;
  let dashboardStore: DashboardStore;

  beforeEach(() => {
    localStorage.removeItem('healops.selectedEnvironmentId');
    sseController = createFakeEventSourceController();

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
        { provide: EVENT_SOURCE_FACTORY, useValue: sseController.factory },
      ],
    });

    httpMock = TestBed.inject(HttpTestingController);
    envStore = TestBed.inject(EnvironmentStore);
    dashboardStore = TestBed.inject(DashboardStore);
  });

  afterEach(() => {
    dashboardStore.ngOnDestroy();
    httpMock.verify();
  });

  it('closes old SSE stream and refreshes overview when switching environments', async () => {
    dashboardStore.start();
    TestBed.flushEffects();
    httpMock.expectOne('/api/v1/overview?environmentId=local').flush(mockOverview('local', 1));
    await Promise.resolve();

    expect(sseController.instances.length).toBe(1);
    const localStream = sseController.instances[0];
    expect(localStream.url).toBe('/api/v1/events?environmentId=local');
    expect(dashboardStore.activeIncidentCount()).toBe(1);

    envStore.selectEnvironment('staging');
    TestBed.flushEffects();

    expect(localStream.closed).toBeTrue();
    expect(sseController.instances.length).toBe(2);
    expect(sseController.instances[1].url).toBe('/api/v1/events?environmentId=staging');
    expect(localStorage.getItem('healops.selectedEnvironmentId')).toBe('staging');

    httpMock.expectOne('/api/v1/overview?environmentId=staging').flush(mockOverview('staging', 3));
    await Promise.resolve();

    expect(dashboardStore.activeIncidentCount()).toBe(3);
  });

  it('surfaces error state cleanly when overview request fails', async () => {
    dashboardStore.start();
    TestBed.flushEffects();
    httpMock
      .expectOne('/api/v1/overview?environmentId=local')
      .flush(
        { type: 'about:blank', title: 'Access denied', status: 403, detail: 'Forbidden environment' },
        { status: 403, statusText: 'Forbidden' }
      );

    await Promise.resolve();
    expect(dashboardStore.error()).toContain('Forbidden environment');
    expect(dashboardStore.loading()).toBeFalse();
  });
});

import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { apiInterceptor } from '../../core/api/api.interceptor';
import { RunbookDetailResponse } from '../../core/api/api.models';
import { AuthStore } from '../../core/state/auth.store';
import { RunbooksComponent } from './runbooks.component';

const DRAFT_RUNBOOK: RunbookDetailResponse = {
  id: 'rb-1',
  name: 'Restart checkout container',
  status: 'DRAFT',
  currentVersion: 1,
  matchCriteria: { service: 'checkout-api' },
  createdAt: '2026-11-22T12:00:00Z',
  updatedAt: '2026-11-22T12:00:00Z',
  latestVersion: {
    id: 'rbv-1',
    runbookId: 'rb-1',
    versionNumber: 1,
    typedSteps: [
      {
        order: 1,
        actionType: 'RESTART_CONTAINER',
        arguments: { containerName: 'checkout-api' },
        description: 'Restart degraded container',
        commandPreview: 'docker restart checkout-api',
        riskLevel: 'MEDIUM',
        rollbackStrategy: null,
      },
    ],
    policyRequirements: {},
    publishedBy: null,
    createdAt: '2026-11-22T12:00:00Z',
  },
  versions: [],
};

describe('RunbooksComponent', () => {
  let httpMock: HttpTestingController;
  let authStore: AuthStore;

  beforeEach(async () => {
    localStorage.removeItem('healops.selectedEnvironmentId');
    await TestBed.configureTestingModule({
      imports: [RunbooksComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    authStore = TestBed.inject(AuthStore);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('gates publish control behind runbook:publish authority', async () => {
    authStore.setUserForTesting({
      username: 'viewer',
      authorities: ['ROLE_VIEWER', 'incident:read', 'env:local'],
    });

    const fixture = TestBed.createComponent(RunbooksComponent);
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/runbooks').flush([DRAFT_RUNBOOK]);
    httpMock
      .expectOne('/api/v1/incidents/page?environmentId=local&status=CLOSED&limit=20')
      .flush({ items: [], nextCursor: null });

    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Requires runbook:publish');
    expect(text).not.toContain('Publish Runbook');
  });

  it('allows operators with runbook:publish to publish a draft runbook', async () => {
    authStore.setUserForTesting({
      username: 'operator',
      authorities: ['ROLE_OPERATOR', 'incident:read', 'incident:write', 'runbook:publish', 'env:local'],
    });

    const fixture = TestBed.createComponent(RunbooksComponent);
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/runbooks').flush([DRAFT_RUNBOOK]);
    httpMock
      .expectOne('/api/v1/incidents/page?environmentId=local&status=CLOSED&limit=20')
      .flush({ items: [], nextCursor: null });

    await fixture.whenStable();
    fixture.detectChanges();

    const publishPromise = fixture.componentInstance.publishSelected(DRAFT_RUNBOOK);
    const req = httpMock.expectOne('/api/v1/runbooks/rb-1/publish');
    expect(req.request.method).toBe('POST');
    req.flush({
      ...DRAFT_RUNBOOK,
      status: 'PUBLISHED',
      latestVersion: {
        ...DRAFT_RUNBOOK.latestVersion!,
        publishedBy: 'operator',
      },
    });

    await publishPromise;
    fixture.detectChanges();

    expect(fixture.componentInstance.selectedRunbook()?.status).toBe('PUBLISHED');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Published by operator');
  });

  it('uses container key in defaultStep and preserves all arguments for multi-argument actions', async () => {
    authStore.setUserForTesting({
      username: 'operator',
      authorities: ['ROLE_OPERATOR', 'incident:read', 'incident:write', 'env:local'],
    });

    const scaleRunbook: RunbookDetailResponse = {
      ...DRAFT_RUNBOOK,
      id: 'rb-scale',
      name: 'Scale checkout deployment',
      latestVersion: {
        ...DRAFT_RUNBOOK.latestVersion!,
        runbookId: 'rb-scale',
        typedSteps: [
          {
            order: 1,
            actionType: 'SCALE_K8S_DEPLOYMENT',
            arguments: { namespace: 'payments', deployment: 'checkout-api', replicas: 4 },
            description: 'Scale deployment up',
            commandPreview: 'kubectl scale deployment/checkout-api --replicas=4 -n payments',
            riskLevel: 'MEDIUM',
            rollbackStrategy: 'RESTORE_PREVIOUS_REPLICAS',
          },
        ],
      },
    };

    const fixture = TestBed.createComponent(RunbooksComponent);
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/runbooks').flush([scaleRunbook]);
    httpMock
      .expectOne('/api/v1/incidents/page?environmentId=local&status=CLOSED&limit=20')
      .flush({ items: [], nextCursor: null });

    await fixture.whenStable();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    const defaultRestart = comp.defaultStep();
    expect(defaultRestart.actionType).toBe('RESTART_CONTAINER');
    expect(defaultRestart.args.map((a) => a.key)).toEqual(['container']);

    // Edit replicas from 4 to 5 and verify all 3 arguments (namespace, deployment, replicas) are preserved
    comp.updateStepArg(0, 2, 'value', '5');
    const savePromise = comp.saveDraft(scaleRunbook);
    const putReq = httpMock.expectOne('/api/v1/runbooks/rb-scale');
    expect(putReq.request.method).toBe('PUT');
    const payload = putReq.request.body as {
      typedSteps: { actionType: string; arguments: Record<string, unknown> }[];
    };
    expect(payload.typedSteps[0].actionType).toBe('SCALE_K8S_DEPLOYMENT');
    expect(payload.typedSteps[0].arguments).toEqual({
      namespace: 'payments',
      deployment: 'checkout-api',
      replicas: 5,
    });
    putReq.flush({ ...scaleRunbook, currentVersion: 2 });
    await savePromise;
  });
});

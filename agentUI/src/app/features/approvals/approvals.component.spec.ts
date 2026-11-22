import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { apiInterceptor } from '../../core/api/api.interceptor';
import { ApprovalQueueItemResponse } from '../../core/api/api.models';
import { EVENT_SOURCE_FACTORY } from '../../core/events/event-source.token';
import { AuthStore } from '../../core/state/auth.store';
import { createFakeEventSourceController } from '../../testing/fake-event-source';
import { ApprovalsComponent } from './approvals.component';

const SAMPLE_APPROVAL: ApprovalQueueItemResponse = {
  actionId: 'act-100',
  incidentId: 'inc-100',
  diagnosisId: 'diag-100',
  environmentId: 'local',
  service: 'checkout-api',
  incidentStatus: 'AWAITING_APPROVAL',
  actionType: 'RESTART_CONTAINER',
  arguments: { containerName: 'checkout-api' },
  riskLevel: 'MEDIUM',
  rollbackStrategy: 'Non-reversible restart; verify health after stabilization',
  status: 'PROPOSED',
  version: 1,
  commandPreview: 'docker restart checkout-api',
  createdAt: '2026-11-22T12:00:00Z',
  latestDecision: null,
};

describe('ApprovalsComponent', () => {
  let httpMock: HttpTestingController;
  let authStore: AuthStore;

  beforeEach(async () => {
    localStorage.removeItem('healops.selectedEnvironmentId');
    const sseController = createFakeEventSourceController();

    await TestBed.configureTestingModule({
      imports: [ApprovalsComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
        { provide: EVENT_SOURCE_FACTORY, useValue: sseController.factory },
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    authStore = TestBed.inject(AuthStore);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('disables approval actions for viewers lacking incident:approve authority', async () => {
    authStore.setUserForTesting({
      username: 'viewer',
      authorities: ['ROLE_VIEWER', 'incident:read', 'env:local'],
    });

    const fixture = TestBed.createComponent(ApprovalsComponent);
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/approvals?environmentId=local&status=PENDING').flush([SAMPLE_APPROVAL]);
    await fixture.whenStable();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Read-only view');
    const approveBtn = Array.from(el.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Approve')
    );
    expect(approveBtn?.disabled).toBeTrue();
  });

  it('prevents duplicate submissions and refreshes queue automatically on 409 Conflict', async () => {
    authStore.setUserForTesting({
      username: 'operator',
      authorities: ['ROLE_OPERATOR', 'incident:read', 'incident:approve', 'env:local'],
    });

    const fixture = TestBed.createComponent(ApprovalsComponent);
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/approvals?environmentId=local&status=PENDING').flush([SAMPLE_APPROVAL]);
    await fixture.whenStable();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    comp.openConfirmation(SAMPLE_APPROVAL, 'APPROVED');
    fixture.detectChanges();

    // Trigger two rapid submissions — only ONE HTTP POST should be issued
    const firstSubmit = comp.confirmDecision();
    const secondSubmit = comp.confirmDecision();

    const postReq = httpMock.expectOne('/api/v1/approvals/act-100/decisions');
    expect(postReq.request.method).toBe('POST');
    expect(postReq.request.headers.get('Idempotency-Key')).toBeTruthy();

    postReq.flush(
      {
        type: 'about:blank',
        title: 'Incident transition conflict',
        status: 409,
        detail: 'Proposed action act-100 version conflict: expected 1 but current version is 2',
      },
      { status: 409, statusText: 'Conflict' }
    );

    // Flush the automatic refresh triggered by 409 Conflict
    await Promise.resolve();
    const refreshReq = httpMock.expectOne('/api/v1/approvals?environmentId=local&status=PENDING');
    refreshReq.flush([]);

    await Promise.all([firstSubmit, secondSubmit]);
    fixture.detectChanges();

    expect(comp.conflictNotice()).toContain('Conflict (409)');
    expect(comp.items().length).toBe(0);
  });
});

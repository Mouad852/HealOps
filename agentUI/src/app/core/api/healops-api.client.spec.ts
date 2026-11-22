import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { apiInterceptor } from './api.interceptor';
import { HealopsApiClient } from './healops-api.client';
import { ApiError } from './problem-detail';

describe('HealopsApiClient & apiInterceptor', () => {
  let api: HealopsApiClient;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    api = TestBed.inject(HealopsApiClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('uses relative /api/v1 URLs, object-form query params, credentials, and correlation header', () => {
    api
      .getIncidentsPage({
        environmentId: 'local',
        status: 'AWAITING_APPROVAL',
        severity: 'HIGH',
        cursor: 'abc123',
        limit: 25,
      })
      .subscribe((page) => {
        expect(page.items.length).toBe(0);
        expect(page.nextCursor).toBeNull();
      });

    const req = httpMock.expectOne(
      (r) =>
        r.url === '/api/v1/incidents/page' &&
        r.params.get('environmentId') === 'local' &&
        r.params.get('status') === 'AWAITING_APPROVAL' &&
        r.params.get('severity') === 'HIGH' &&
        r.params.get('cursor') === 'abc123' &&
        r.params.get('limit') === '25'
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.withCredentials).toBeTrue();
    expect(req.request.headers.get('X-Correlation-Id')).toBeTruthy();

    req.flush({ items: [], nextCursor: null });
  });

  it('attaches Idempotency-Key header on approval decision submissions', () => {
    api
      .submitApprovalDecision('act-1', {
        decision: 'APPROVED',
        reason: 'Verified evidence',
        expectedVersion: 2,
        idempotencyKey: 'idem-key-123',
      })
      .subscribe();

    const req = httpMock.expectOne('/api/v1/approvals/act-1/decisions');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Idempotency-Key')).toBe('idem-key-123');
    expect(req.request.withCredentials).toBeTrue();
    req.flush({});
  });

  it('normalizes RFC 9457 ProblemDetail error responses into ApiError', () => {
    let capturedError: unknown = null;

    api
      .submitApprovalDecision('act-stale', {
        decision: 'APPROVED',
        reason: 'Stale test',
        expectedVersion: 1,
        idempotencyKey: 'idem-stale',
      })
      .subscribe({
        error: (err: unknown) => {
          capturedError = err;
        },
      });

    const req = httpMock.expectOne('/api/v1/approvals/act-stale/decisions');
    req.flush(
      {
        type: 'about:blank',
        title: 'Incident transition conflict',
        status: 409,
        detail: 'Proposed action version conflict: expected 1 but current version is 2',
      },
      { status: 409, statusText: 'Conflict' }
    );

    expect(capturedError instanceof ApiError).toBeTrue();
    const apiErr = capturedError as ApiError;
    expect(apiErr.status).toBe(409);
    expect(apiErr.isConflict).toBeTrue();
    expect(apiErr.problem.title).toBe('Incident transition conflict');
    expect(apiErr.problem.detail).toContain('version conflict');
    expect(apiErr.correlationId).toBeTruthy();
  });
});

import { HttpErrorResponse } from '@angular/common/http';

export interface ProblemDetail {
  readonly type: string;
  readonly title: string;
  readonly status: number;
  readonly detail: string;
  readonly instance?: string;
  readonly policyVersion?: string;
  readonly ruleId?: string;
  readonly errors?: Readonly<Record<string, string>>;
}

export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail;
  readonly correlationId: string;

  constructor(problem: ProblemDetail, correlationId: string) {
    super(problem.detail || problem.title || `HTTP ${problem.status}`);
    this.name = 'ApiError';
    this.status = problem.status;
    this.problem = problem;
    this.correlationId = correlationId;
  }

  get isConflict(): boolean {
    return this.status === 409;
  }

  get isForbidden(): boolean {
    return this.status === 403;
  }

  get isUnauthorized(): boolean {
    return this.status === 401;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function extractStringRecord(value: unknown): Readonly<Record<string, string>> | undefined {
  if (!isRecord(value)) {
    return undefined;
  }
  const result: Record<string, string> = {};
  for (const [k, v] of Object.entries(value)) {
    if (typeof v === 'string') {
      result[k] = v;
    }
  }
  return result;
}

export function normalizeHttpError(error: HttpErrorResponse, correlationId: string): ApiError {
  const status = typeof error.status === 'number' ? error.status : 0;
  const body = error.error;

  if (isRecord(body)) {
    const title = typeof body['title'] === 'string' && body['title'].trim().length > 0
      ? body['title']
      : defaultTitleForStatus(status);
    const detail = typeof body['detail'] === 'string' && body['detail'].trim().length > 0
      ? body['detail']
      : (typeof body['message'] === 'string' && body['message'].trim().length > 0
          ? body['message']
          : error.message || defaultTitleForStatus(status));
    const type = typeof body['type'] === 'string' ? body['type'] : 'about:blank';
    const instance = typeof body['instance'] === 'string' ? body['instance'] : undefined;
    const policyVersion = typeof body['policyVersion'] === 'string' ? body['policyVersion'] : undefined;
    const ruleId = typeof body['ruleId'] === 'string' ? body['ruleId'] : undefined;
    const errors = extractStringRecord(body['errors']);

    const problem: ProblemDetail = {
      type,
      title,
      status: typeof body['status'] === 'number' ? body['status'] : status,
      detail,
      instance,
      policyVersion,
      ruleId,
      errors,
    };
    return new ApiError(problem, correlationId);
  }

  const fallbackDetail =
    typeof body === 'string' && body.trim().length > 0
      ? body.trim()
      : status === 0
        ? 'Unable to reach HealOps control plane. Check your network connection.'
        : error.statusText || defaultTitleForStatus(status);

  return new ApiError(
    {
      type: 'about:blank',
      title: defaultTitleForStatus(status),
      status,
      detail: fallbackDetail,
    },
    correlationId
  );
}

export function toApiErrorMessage(err: unknown, fallback = 'An unexpected error occurred.'): string {
  if (err instanceof ApiError) {
    return err.problem.detail || err.problem.title || fallback;
  }
  if (err instanceof Error && err.message) {
    return err.message;
  }
  return fallback;
}

function defaultTitleForStatus(status: number): string {
  switch (status) {
    case 0:
      return 'Network error';
    case 400:
      return 'Invalid request';
    case 401:
      return 'Authentication required';
    case 403:
      return 'Access denied';
    case 404:
      return 'Resource not found';
    case 409:
      return 'Conflict';
    case 429:
      return 'Rate limit exceeded';
    default:
      return status >= 500 ? 'Server error' : `HTTP ${status}`;
  }
}

import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';
import { normalizeHttpError } from './problem-detail';

export function generateCorrelationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const rand = Math.random().toString(16).slice(2, 14);
  return `corr-${Date.now().toString(16)}-${rand}`;
}

export function readCookieValue(cookieHeader: string, name: string): string | null {
  if (!cookieHeader) {
    return null;
  }
  const parts = cookieHeader.split(';');
  const prefix = `${name}=`;
  for (const rawPart of parts) {
    const trimmed = rawPart.trim();
    if (trimmed.startsWith(prefix)) {
      return decodeURIComponent(trimmed.slice(prefix.length));
    }
  }
  return null;
}

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

export const apiInterceptor: HttpInterceptorFn = (req, next) => {
  const correlationId = req.headers.get('X-Correlation-Id') ?? generateCorrelationId();

  let headers = req.headers
    .set('X-Correlation-Id', correlationId)
    .set('Accept', req.headers.get('Accept') ?? 'application/json');

  if (MUTATING_METHODS.has(req.method.toUpperCase()) && !headers.has('X-XSRF-TOKEN')) {
    const cookieString = typeof document !== 'undefined' ? document.cookie : '';
    const xsrfToken = readCookieValue(cookieString, 'XSRF-TOKEN');
    if (xsrfToken) {
      headers = headers.set('X-XSRF-TOKEN', xsrfToken);
    }
  }

  const normalizedReq = req.clone({
    withCredentials: true,
    headers,
  });

  return next(normalizedReq).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse) {
        return throwError(() => normalizeHttpError(err, correlationId));
      }
      return throwError(() => err);
    })
  );
};

import { Injectable, NgZone, OnDestroy, inject, signal } from '@angular/core';
import { Observable, Subject, filter } from 'rxjs';
import {
  DASHBOARD_EVENT_TYPES,
  DashboardEventEnvelope,
  DashboardEventType,
} from '../api/api.models';
import { EVENT_SOURCE_FACTORY, EventSourceLike } from './event-source.token';

export type SseConnectionState = 'connecting' | 'connected' | 'reconnecting' | 'disconnected';

interface RegisteredListener {
  readonly eventType: DashboardEventType;
  readonly listener: (event: MessageEvent<string>) => void;
}

@Injectable({ providedIn: 'root' })
export class DashboardEventsService implements OnDestroy {
  private readonly eventSourceFactory = inject(EVENT_SOURCE_FACTORY);
  private readonly ngZone = inject(NgZone);

  private readonly _connectionState = signal<SseConnectionState>('disconnected');
  readonly connectionState = this._connectionState.asReadonly();

  private readonly _activeEnvironmentId = signal<string | null>(null);
  readonly activeEnvironmentId = this._activeEnvironmentId.asReadonly();

  private readonly _lastSequenceNumber = signal<number | null>(null);
  readonly lastSequenceNumber = this._lastSequenceNumber.asReadonly();

  private readonly eventsSubject = new Subject<DashboardEventEnvelope>();
  readonly events$: Observable<DashboardEventEnvelope> = this.eventsSubject.asObservable();

  private currentSource: EventSourceLike | null = null;
  private currentListeners: RegisteredListener[] = [];

  connect(environmentId: string): void {
    const normalizedEnv = environmentId.trim();
    if (!normalizedEnv) {
      this.disconnect();
      return;
    }

    if (this._activeEnvironmentId() === normalizedEnv && this.currentSource !== null) {
      return;
    }

    this.closeCurrentSource();
    this._activeEnvironmentId.set(normalizedEnv);
    this._lastSequenceNumber.set(null);
    this._connectionState.set('connecting');

    const params = new URLSearchParams({ environmentId: normalizedEnv });
    const url = `/api/v1/events?${params.toString()}`;
    const source = this.eventSourceFactory(url, { withCredentials: true });
    this.currentSource = source;

    source.onopen = () => {
      this.ngZone.run(() => {
        if (this.currentSource === source) {
          this._connectionState.set('connected');
        }
      });
    };

    source.onerror = () => {
      this.ngZone.run(() => {
        if (this.currentSource !== source) {
          return;
        }
        // Native EventSource automatically retries with Last-Event-ID on transient errors.
        // Never terminate eventsSubject on transient errors.
        const closedState = typeof EventSource !== 'undefined' ? EventSource.CLOSED : 2;
        if (source.readyState === closedState) {
          this._connectionState.set('disconnected');
        } else {
          this._connectionState.set('reconnecting');
        }
      });
    };

    const listeners: RegisteredListener[] = [];
    for (const eventType of DASHBOARD_EVENT_TYPES) {
      const listener = (message: MessageEvent<string>) => {
        this.handleNamedEvent(source, eventType, message);
      };
      source.addEventListener(eventType, listener);
      listeners.push({ eventType, listener });
    }
    this.currentListeners = listeners;
  }

  disconnect(): void {
    this.closeCurrentSource();
    this._activeEnvironmentId.set(null);
    this._connectionState.set('disconnected');
  }

  ofType(...types: readonly DashboardEventType[]): Observable<DashboardEventEnvelope> {
    const typeSet = new Set<DashboardEventType>(types);
    return this.events$.pipe(filter((envelope) => typeSet.has(envelope.type)));
  }

  ngOnDestroy(): void {
    this.disconnect();
    this.eventsSubject.complete();
  }

  private handleNamedEvent(
    source: EventSourceLike,
    fallbackType: DashboardEventType,
    message: MessageEvent<string>
  ): void {
    if (this.currentSource !== source) {
      return;
    }
    const parsed = this.parseEnvelope(message.data, fallbackType);
    if (!parsed) {
      return;
    }
    this.ngZone.run(() => {
      if (this.currentSource !== source) {
        return;
      }
      if (this._connectionState() !== 'connected') {
        this._connectionState.set('connected');
      }
      if (typeof parsed.sequenceNumber === 'number') {
        this._lastSequenceNumber.set(parsed.sequenceNumber);
      }
      this.eventsSubject.next(parsed);
    });
  }

  private parseEnvelope(rawData: string, fallbackType: DashboardEventType): DashboardEventEnvelope | null {
    if (!rawData) {
      return null;
    }
    try {
      const parsed: unknown = JSON.parse(rawData);
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
        return null;
      }
      const record = parsed as Record<string, unknown>;
      const id = typeof record['id'] === 'string' ? record['id'] : '';
      const environmentId = typeof record['environmentId'] === 'string' ? record['environmentId'] : '';
      const occurredAt = typeof record['occurredAt'] === 'string' ? record['occurredAt'] : new Date().toISOString();
      const rawType = typeof record['type'] === 'string' ? record['type'] : fallbackType;
      const eventType = this.isDashboardEventType(rawType) ? rawType : fallbackType;
      const sequenceNumber =
        typeof record['sequenceNumber'] === 'number' ? record['sequenceNumber'] : 0;
      const incidentId = typeof record['incidentId'] === 'string' ? record['incidentId'] : null;
      const traceId = typeof record['traceId'] === 'string' ? record['traceId'] : null;
      const entityVersion =
        typeof record['entityVersion'] === 'number' ? record['entityVersion'] : null;
      const schemaVersion =
        typeof record['schemaVersion'] === 'string' ? record['schemaVersion'] : 'v1';
      const payload =
        typeof record['payload'] === 'object' && record['payload'] !== null && !Array.isArray(record['payload'])
          ? (record['payload'] as Record<string, unknown>)
          : {};

      return {
        schemaVersion,
        id,
        sequenceNumber,
        type: eventType,
        occurredAt,
        environmentId,
        incidentId,
        traceId,
        entityVersion,
        payload,
      };
    } catch {
      return null;
    }
  }

  private isDashboardEventType(value: string): value is DashboardEventType {
    return (DASHBOARD_EVENT_TYPES as readonly string[]).includes(value);
  }

  private closeCurrentSource(): void {
    if (this.currentSource) {
      for (const item of this.currentListeners) {
        this.currentSource.removeEventListener(item.eventType, item.listener);
      }
      this.currentSource.onopen = null;
      this.currentSource.onerror = null;
      this.currentSource.close();
      this.currentSource = null;
      this.currentListeners = [];
    }
  }
}

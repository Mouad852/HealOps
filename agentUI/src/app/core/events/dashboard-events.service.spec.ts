import { TestBed } from '@angular/core/testing';
import { DASHBOARD_EVENT_TYPES, DashboardEventEnvelope } from '../api/api.models';
import {
  createFakeEventSourceController,
  FakeEventSourceController,
} from '../../testing/fake-event-source';
import { DashboardEventsService } from './dashboard-events.service';
import { EVENT_SOURCE_FACTORY } from './event-source.token';

describe('DashboardEventsService', () => {
  let service: DashboardEventsService;
  let controller: FakeEventSourceController;

  beforeEach(() => {
    controller = createFakeEventSourceController();
    TestBed.configureTestingModule({
      providers: [{ provide: EVENT_SOURCE_FACTORY, useValue: controller.factory }],
    });
    service = TestBed.inject(DashboardEventsService);
  });

  afterEach(() => {
    service.ngOnDestroy();
  });

  it('maintains one shared EventSource per environment and registers all 7 named events', () => {
    service.connect('local');
    service.connect('local'); // idempotent — must not open a second connection

    expect(controller.instances.length).toBe(1);
    const source = controller.latest()!;
    expect(source.url).toBe('/api/v1/events?environmentId=local');
    expect(source.init?.withCredentials).toBeTrue();
    expect(service.connectionState()).toBe('connecting');

    for (const eventType of DASHBOARD_EVENT_TYPES) {
      expect(source.listeners.has(eventType)).toBeTrue();
    }

    source.emitOpen();
    expect(service.connectionState()).toBe('connected');
  });

  it('parses typed envelopes and keeps stream alive during transient reconnect errors', () => {
    const received: DashboardEventEnvelope[] = [];
    let errored = false;
    let completed = false;

    const sub = service.events$.subscribe({
      next: (ev) => received.push(ev),
      error: () => {
        errored = true;
      },
      complete: () => {
        completed = true;
      },
    });

    service.connect('local');
    const source = controller.latest()!;
    source.emitOpen();

    // Transient error -> reconnecting without terminating observable
    source.emitError(0);
    expect(service.connectionState()).toBe('reconnecting');
    expect(errored).toBeFalse();
    expect(completed).toBeFalse();

    source.emitNamedEvent('incident.updated', {
      schemaVersion: 'v1',
      id: 'evt-1',
      sequenceNumber: 42,
      type: 'incident.updated',
      occurredAt: '2026-11-22T12:00:00Z',
      environmentId: 'local',
      incidentId: 'inc-1',
      traceId: 'trace-1',
      entityVersion: 3,
      payload: { status: 'AWAITING_APPROVAL' },
    });

    expect(service.connectionState()).toBe('connected');
    expect(service.lastSequenceNumber()).toBe(42);
    expect(received.length).toBe(1);
    expect(received[0].type).toBe('incident.updated');
    expect(received[0].payload['status']).toBe('AWAITING_APPROVAL');

    sub.unsubscribe();
  });

  it('closes the previous EventSource when switching environments', () => {
    service.connect('local');
    const first = controller.instances[0];
    first.emitOpen();

    service.connect('staging');
    expect(first.closed).toBeTrue();
    expect(controller.instances.length).toBe(2);
    expect(controller.instances[1].url).toBe('/api/v1/events?environmentId=staging');
    expect(service.activeEnvironmentId()).toBe('staging');
  });
});

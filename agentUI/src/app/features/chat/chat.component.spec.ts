import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { apiInterceptor } from '../../core/api/api.interceptor';
import { DashboardEventsService } from '../../core/events/dashboard-events.service';
import { EVENT_SOURCE_FACTORY } from '../../core/events/event-source.token';
import {
  createFakeEventSourceController,
  FakeEventSourceController,
} from '../../testing/fake-event-source';
import { ChatComponent } from './chat.component';

describe('ChatComponent', () => {
  let httpMock: HttpTestingController;
  let sseController: FakeEventSourceController;
  let sseService: DashboardEventsService;

  beforeEach(async () => {
    localStorage.removeItem('healops.selectedEnvironmentId');
    sseController = createFakeEventSourceController();

    await TestBed.configureTestingModule({
      imports: [ChatComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
        { provide: EVENT_SOURCE_FACTORY, useValue: sseController.factory },
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    sseService = TestBed.inject(DashboardEventsService);
    sseService.connect('local');
    sseController.latest()?.emitOpen();
  });

  afterEach(() => {
    sseService.ngOnDestroy();
    httpMock.verify();
  });

  it('uses persisted conversation APIs and streams tokens via shared SSE channel', async () => {
    const fixture = TestBed.createComponent(ChatComponent);
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/conversations?environmentId=local').flush([
      {
        id: 'sess-1',
        environmentId: 'local',
        title: 'Ops session (local)',
        createdAt: '2026-11-22T12:00:00Z',
        updatedAt: '2026-11-22T12:00:00Z',
      },
    ]);

    await fixture.whenStable();
    httpMock.expectOne('/api/v1/conversations/sess-1').flush({
      id: 'sess-1',
      environmentId: 'local',
      title: 'Ops session (local)',
      createdAt: '2026-11-22T12:00:00Z',
      updatedAt: '2026-11-22T12:00:00Z',
      messages: [],
    });

    await fixture.whenStable();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    comp.draftMessage.set('Check incident inc-42 status');
    const sendPromise = comp.sendMessage();

    const postReq = httpMock.expectOne('/api/v1/conversations/sess-1/messages');
    expect(postReq.request.method).toBe('POST');
    postReq.flush(
      {
        messageId: 'msg-1',
        sessionId: 'sess-1',
        role: 'USER',
        content: 'Check incident inc-42 status',
        evidenceId: null,
        executionId: null,
        incidentId: null,
        createdAt: '2026-11-22T12:01:00Z',
        status: 'ACCEPTED',
      },
      { status: 202, statusText: 'Accepted' }
    );

    await sendPromise;
    fixture.detectChanges();

    // Stream token chunk over shared SSE
    sseController.latest()?.emitNamedEvent('conversation.token', {
      schemaVersion: 'v1',
      id: 'ev-tok-1',
      sequenceNumber: 10,
      type: 'conversation.token',
      occurredAt: '2026-11-22T12:01:01Z',
      environmentId: 'local',
      incidentId: null,
      traceId: null,
      entityVersion: null,
      payload: { sessionId: 'sess-1', token: 'Investigating incident inc-42.' },
    });
    fixture.detectChanges();

    expect(comp.streamingReply()).toBe('Investigating incident inc-42.');

    // Stream completion reloads persisted conversation messages
    sseController.latest()?.emitNamedEvent('conversation.completed', {
      schemaVersion: 'v1',
      id: 'ev-done-1',
      sequenceNumber: 11,
      type: 'conversation.completed',
      occurredAt: '2026-11-22T12:01:02Z',
      environmentId: 'local',
      incidentId: null,
      traceId: null,
      entityVersion: null,
      payload: { sessionId: 'sess-1', status: 'COMPLETED' },
    });

    const reloadReq = httpMock.expectOne('/api/v1/conversations/sess-1');
    reloadReq.flush({
      id: 'sess-1',
      environmentId: 'local',
      title: 'Ops session (local)',
      createdAt: '2026-11-22T12:00:00Z',
      updatedAt: '2026-11-22T12:01:02Z',
      messages: [
        {
          id: 'msg-1',
          sessionId: 'sess-1',
          role: 'USER',
          content: 'Check incident inc-42 status',
          evidenceId: null,
          executionId: null,
          incidentId: null,
          createdAt: '2026-11-22T12:01:00Z',
        },
        {
          id: 'msg-2',
          sessionId: 'sess-1',
          role: 'ASSISTANT',
          content: 'Investigating incident inc-42.',
          evidenceId: null,
          executionId: null,
          incidentId: 'inc-42',
          createdAt: '2026-11-22T12:01:02Z',
        },
      ],
    });

    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp.streamingReply()).toBe('');
    expect(comp.activeSession()?.messages.length).toBe(2);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Incident inc-42');
  });

  it('falls back to polling when SSE misses conversation.completed', fakeAsync(() => {
    const fixture = TestBed.createComponent(ChatComponent);
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/conversations?environmentId=local').flush([
      {
        id: 'sess-poll',
        environmentId: 'local',
        title: 'Polling fallback session',
        createdAt: '2026-11-22T12:00:00Z',
        updatedAt: '2026-11-22T12:00:00Z',
      },
    ]);
    tick();

    httpMock.expectOne('/api/v1/conversations/sess-poll').flush({
      id: 'sess-poll',
      environmentId: 'local',
      title: 'Polling fallback session',
      createdAt: '2026-11-22T12:00:00Z',
      updatedAt: '2026-11-22T12:00:00Z',
      messages: [],
    });
    tick();

    const comp = fixture.componentInstance;
    comp.draftMessage.set('Check active incidents');
    void comp.sendMessage();

    httpMock.expectOne('/api/v1/conversations/sess-poll/messages').flush(
      {
        messageId: 'msg-u1',
        sessionId: 'sess-poll',
        role: 'USER',
        content: 'Check active incidents',
        evidenceId: null,
        executionId: null,
        incidentId: null,
        createdAt: '2026-11-22T12:01:00Z',
        status: 'ACCEPTED',
      },
      { status: 202, statusText: 'Accepted' }
    );
    tick();
    expect(comp.awaitingAssistant()).toBeTrue();

    // Advance past POLL_INTERVAL_MS without SSE conversation.completed
    tick(ChatComponent.POLL_INTERVAL_MS);

    httpMock.expectOne('/api/v1/conversations/sess-poll').flush({
      id: 'sess-poll',
      environmentId: 'local',
      title: 'Polling fallback session',
      createdAt: '2026-11-22T12:00:00Z',
      updatedAt: '2026-11-22T12:01:03Z',
      messages: [
        {
          id: 'msg-u1',
          sessionId: 'sess-poll',
          role: 'USER',
          content: 'Check active incidents',
          evidenceId: null,
          executionId: null,
          incidentId: null,
          createdAt: '2026-11-22T12:01:00Z',
        },
        {
          id: 'msg-a1',
          sessionId: 'sess-poll',
          role: 'ASSISTANT',
          content: '1 active incident in local.',
          evidenceId: null,
          executionId: null,
          incidentId: null,
          createdAt: '2026-11-22T12:01:03Z',
        },
      ],
    });
    tick();

    expect(comp.awaitingAssistant()).toBeFalse();
    expect(comp.activeSession()?.messages.length).toBe(2);
    expect(comp.activeSession()?.messages[1].content).toBe('1 active incident in local.');
  }));

  it('sends quick investigation prompt chip on click', fakeAsync(() => {
    const fixture = TestBed.createComponent(ChatComponent);
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/conversations?environmentId=local').flush([
      {
        id: 'sess-quick',
        environmentId: 'local',
        title: 'Quick prompt session',
        createdAt: '2026-11-22T12:00:00Z',
        updatedAt: '2026-11-22T12:00:00Z',
      },
    ]);
    tick();

    httpMock.expectOne('/api/v1/conversations/sess-quick').flush({
      id: 'sess-quick',
      environmentId: 'local',
      title: 'Quick prompt session',
      createdAt: '2026-11-22T12:00:00Z',
      updatedAt: '2026-11-22T12:00:00Z',
      messages: [],
    });
    tick();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    comp.sendQuickPrompt(comp.quickPrompts[0]);

    const postReq = httpMock.expectOne('/api/v1/conversations/sess-quick/messages');
    expect(postReq.request.body.content).toBe('Analyze Loki logs & traces for this incident');
    postReq.flush(
      {
        messageId: 'msg-q1',
        sessionId: 'sess-quick',
        role: 'USER',
        content: 'Analyze Loki logs & traces for this incident',
        evidenceId: null,
        executionId: null,
        incidentId: null,
        createdAt: '2026-11-22T12:01:00Z',
        status: 'ACCEPTED',
      },
      { status: 202, statusText: 'Accepted' }
    );
    tick();
    comp.awaitingAssistant.set(false);
    tick(ChatComponent.POLL_INTERVAL_MS);
  }));
});

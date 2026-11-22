import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import {
  ConversationDetailResponse,
  ConversationMessageResponse,
  ConversationSessionResponse,
} from '../../core/api/api.models';
import { HealopsApiClient } from '../../core/api/healops-api.client';
import { toApiErrorMessage } from '../../core/api/problem-detail';
import { DashboardEventsService } from '../../core/events/dashboard-events.service';
import { EnvironmentStore } from '../../core/state/environment.store';
import { StatusBadgeComponent } from '../../shared/status-badge/status-badge.component';

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="row g-3" aria-label="HealOps Copilot persisted chat">
      <aside class="col-lg-3">
        <div class="card healops-surface h-100">
          <div class="card-header border-secondary-subtle d-flex justify-content-between align-items-center">
            <h1 class="h6 mb-0 text-white">Sessions ({{ envStore.selectedEnvironmentId() }})</h1>
            <button
              type="button"
              class="btn btn-sm btn-info fw-semibold"
              [disabled]="creatingSession()"
              (click)="createNewSession()"
            >
              <i class="bi bi-plus-lg me-1" aria-hidden="true"></i>New
            </button>
          </div>
          <div class="list-group list-group-flush">
            @if (loadingSessions() && sessions().length === 0) {
              <div class="p-3 small text-secondary" role="status">Loading sessions&hellip;</div>
            } @else if (sessions().length === 0) {
              <div class="p-3 small text-secondary">No sessions in this environment yet.</div>
            } @else {
              @for (session of sessions(); track session.id) {
                <button
                  type="button"
                  class="list-group-item list-group-item-action bg-transparent text-light border-secondary-subtle text-start"
                  [class.active]="activeSession()?.id === session.id"
                  (click)="selectSession(session.id)"
                >
                  <div class="fw-semibold small text-truncate">{{ session.title }}</div>
                  <div class="small text-secondary">
                    <time [attr.datetime]="session.updatedAt">{{ session.updatedAt | date: 'short' }}</time>
                  </div>
                </button>
              }
            }
          </div>
        </div>
      </aside>

      <div class="col-lg-9">
        <div class="card healops-surface d-flex flex-column chat-panel">
          <div class="card-header border-secondary-subtle d-flex flex-wrap justify-content-between align-items-center gap-2">
            <div>
              <h2 class="h6 mb-0 text-white">
                {{ activeSession()?.title || 'HealOps Operations Copilot' }}
              </h2>
              <div class="small text-secondary">
                Persisted conversation &bull; Live replies streamed via shared SSE channel
              </div>
            </div>
            <app-status-badge [value]="sse.connectionState()" prefix="Stream status" />
          </div>

          @if (error(); as err) {
            <div class="alert alert-danger m-3 mb-0 py-2 small" role="alert">{{ err }}</div>
          }

          <div class="card-body overflow-auto d-flex flex-column gap-3 chat-messages">
            @if (!activeSession()) {
              <div class="text-center text-secondary py-5">
                <i class="bi bi-chat-square-text fs-1 d-block mb-2 text-info" aria-hidden="true"></i>
                Select an existing session or start a new environment-scoped conversation.
              </div>
            } @else {
              @if (activeSession()!.messages.length === 0 && !streamingReply()) {
                <p class="text-secondary small text-center my-4">
                  Ask about active incidents, evidence provenance, or remediation status in
                  <strong>{{ activeSession()!.environmentId }}</strong>.
                </p>
              }

              @for (msg of activeSession()!.messages; track msg.id) {
                <article
                  class="p-3 rounded border"
                  [class.border-info]="msg.role === 'USER'"
                  [class.border-secondary-subtle]="msg.role !== 'USER'"
                  [class.bg-dark]="msg.role !== 'USER'"
                  [class.ms-auto]="msg.role === 'USER'"
                  style="max-width: 85%;"
                >
                  <div class="d-flex justify-content-between align-items-center gap-2 mb-1 small">
                    <app-status-badge [value]="roleBadgeTone(msg.role)" [label]="msg.role" prefix="Message role" />
                    <time class="text-secondary" [attr.datetime]="msg.createdAt">{{ msg.createdAt | date: 'shortTime' }}</time>
                  </div>
                  <div class="small text-light" style="white-space: pre-wrap;">{{ msg.content }}</div>

                  @if (msg.incidentId || msg.evidenceId || msg.executionId) {
                    <div class="d-flex flex-wrap gap-2 mt-2 pt-2 border-top border-secondary-subtle small font-monospace">
                      @if (msg.incidentId) {
                        <a class="link-info text-decoration-none" [routerLink]="['/incidents', msg.incidentId]">
                          <i class="bi bi-exclamation-octagon me-1" aria-hidden="true"></i>Incident {{ msg.incidentId.slice(0, 8) }}
                        </a>
                      }
                      @if (msg.evidenceId) {
                        <span class="text-secondary">Evidence: {{ msg.evidenceId.slice(0, 8) }}</span>
                      }
                      @if (msg.executionId) {
                        <span class="text-secondary">Execution: {{ msg.executionId.slice(0, 8) }}</span>
                      }
                    </div>
                  }
                </article>
              }

              @if (streamingReply() || awaitingAssistant()) {
                <article class="p-3 rounded border border-info-subtle bg-dark" style="max-width: 85%;" aria-live="polite">
                  <div class="d-flex align-items-center gap-2 mb-1 small">
                    <span class="spinner-grow spinner-grow-sm text-info" aria-hidden="true"></span>
                    <strong class="text-info">ASSISTANT (streaming)</strong>
                  </div>
                  <div class="small text-light" style="white-space: pre-wrap;">
                    {{ streamingReply() || 'Processing request asynchronously...' }}
                  </div>
                </article>
              }
            }
          </div>

          <form class="card-footer border-secondary-subtle d-flex flex-column gap-2" (ngSubmit)="sendMessage()">
            <div class="d-flex flex-wrap align-items-center gap-2 small">
              <label for="linkedIncidentInput" class="text-secondary mb-0">Linked Incident UUID (optional):</label>
              <input
                id="linkedIncidentInput"
                name="linkedIncidentId"
                type="text"
                class="form-control form-control-sm bg-dark text-white border-secondary font-monospace"
                style="max-width: 22rem;"
                placeholder="Paste incident UUID to attach context"
                [ngModel]="linkedIncidentId()"
                (ngModelChange)="linkedIncidentId.set($event)"
              />
            </div>

            <div class="input-group">
              <label for="chatMessageInput" class="visually-hidden">Message to HealOps Copilot</label>
              <input
                id="chatMessageInput"
                name="chatMessage"
                type="text"
                class="form-control bg-dark text-white border-secondary"
                placeholder="Ask HealOps Copilot..."
                [ngModel]="draftMessage()"
                (ngModelChange)="draftMessage.set($event)"
                [disabled]="sending()"
              />
              <button
                type="submit"
                class="btn btn-info fw-semibold"
                [disabled]="sending() || !draftMessage().trim()"
              >
                @if (sending()) {
                  <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
                }
                Send
              </button>
            </div>
          </form>
        </div>
      </div>
    </section>
  `,
  styles: [`
    .chat-panel { min-height: 70vh; }
    .chat-messages { max-height: 55vh; }
  `],
})
export class ChatComponent {
  readonly envStore = inject(EnvironmentStore);
  readonly sse = inject(DashboardEventsService);
  private readonly api = inject(HealopsApiClient);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  readonly sessions = signal<readonly ConversationSessionResponse[]>([]);
  readonly activeSession = signal<ConversationDetailResponse | null>(null);
  readonly draftMessage = signal<string>('');
  readonly linkedIncidentId = signal<string>('');
  readonly streamingReply = signal<string>('');
  readonly awaitingAssistant = signal<boolean>(false);
  readonly loadingSessions = signal<boolean>(false);
  readonly creatingSession = signal<boolean>(false);
  readonly sending = signal<boolean>(false);
  readonly error = signal<string | null>(null);

  static readonly POLL_INTERVAL_MS = 2500;
  static readonly POLL_TIMEOUT_MS = 30000;

  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  private pollStartedAt = 0;
  private baselineMessageCount = 0;

  constructor() {
    this.destroyRef.onDestroy(() => this.clearAssistantPollTimer());

    this.route.queryParamMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => {
        const incId = params.get('incidentId');
        if (incId) {
          this.linkedIncidentId.set(incId);
        }
      });

    effect(() => {
      const envId = this.envStore.selectedEnvironmentId();
      if (envId) {
        this.clearAssistantPollTimer();
        this.activeSession.set(null);
        this.streamingReply.set('');
        this.awaitingAssistant.set(false);
        void this.loadSessions(envId);
      }
    });

    this.sse
      .ofType('conversation.token', 'conversation.completed')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((envelope) => {
        const current = this.activeSession();
        const eventSessionId = envelope.payload['sessionId'];
        if (!current || typeof eventSessionId !== 'string' || eventSessionId !== current.id) {
          return;
        }
        if (envelope.type === 'conversation.token') {
          const token = envelope.payload['token'];
          if (typeof token === 'string') {
            this.streamingReply.update((prev) => prev + token);
          }
        } else if (envelope.type === 'conversation.completed') {
          this.clearAssistantPollTimer();
          this.streamingReply.set('');
          this.awaitingAssistant.set(false);
          void this.selectSession(current.id);
        }
      });
  }

  roleBadgeTone(role: ConversationMessageResponse['role']): string {
    switch (role) {
      case 'USER':
        return 'INFO';
      case 'ASSISTANT':
        return 'OK';
      case 'ERROR':
        return 'ERROR';
      case 'TOOL_REQUEST':
      case 'TOOL_RESPONSE':
        return 'WARNING';
      default:
        return 'NEUTRAL';
    }
  }

  async createNewSession(): Promise<ConversationSessionResponse | null> {
    if (this.creatingSession()) {
      return null;
    }
    this.creatingSession.set(true);
    this.error.set(null);
    const envId = this.envStore.selectedEnvironmentId();
    try {
      const created = await firstValueFrom(
        this.api.createConversation({
          environmentId: envId,
          title: `Ops session (${envId})`,
        })
      );
      this.sessions.update((list) => [created, ...list]);
      await this.selectSession(created.id);
      return created;
    } catch (err: unknown) {
      this.error.set(toApiErrorMessage(err, 'Unable to create conversation session.'));
      return null;
    } finally {
      this.creatingSession.set(false);
    }
  }

  async selectSession(sessionId: string): Promise<void> {
    this.clearAssistantPollTimer();
    this.awaitingAssistant.set(false);
    this.streamingReply.set('');
    this.error.set(null);
    try {
      const detail = await firstValueFrom(this.api.getConversation(sessionId));
      this.activeSession.set(detail);
    } catch (err: unknown) {
      this.error.set(toApiErrorMessage(err, 'Unable to load conversation messages.'));
    }
  }

  async sendMessage(): Promise<void> {
    const content = this.draftMessage().trim();
    if (!content || this.sending()) {
      return;
    }
    this.sending.set(true);
    this.error.set(null);

    try {
      let sessionId = this.activeSession()?.id ?? null;
      if (!sessionId) {
        const created = await this.createNewSession();
        sessionId = created?.id ?? null;
      }
      if (!sessionId) {
        return;
      }

      const incidentId = this.linkedIncidentId().trim() || undefined;
      const accepted = await firstValueFrom(
        this.api.postConversationMessage(sessionId, {
          content,
          incidentId,
        })
      );

      this.draftMessage.set('');
      this.streamingReply.set('');
      this.awaitingAssistant.set(true);

      this.activeSession.update((detail) => {
        if (!detail || detail.id !== sessionId) {
          return detail;
        }
        const appended: ConversationMessageResponse = {
          id: accepted.messageId,
          sessionId: accepted.sessionId,
          role: accepted.role,
          content: accepted.content,
          evidenceId: accepted.evidenceId,
          executionId: accepted.executionId,
          incidentId: accepted.incidentId,
          createdAt: accepted.createdAt,
        };
        return {
          ...detail,
          messages: [...detail.messages, appended],
        };
      });

      this.baselineMessageCount = this.activeSession()?.messages.length ?? 0;
      this.startAssistantPollFallback(sessionId);
    } catch (err: unknown) {
      this.error.set(toApiErrorMessage(err, 'Unable to post message.'));
      this.awaitingAssistant.set(false);
      this.clearAssistantPollTimer();
    } finally {
      this.sending.set(false);
    }
  }

  private startAssistantPollFallback(sessionId: string): void {
    this.clearAssistantPollTimer();
    this.pollStartedAt = Date.now();
    this.scheduleAssistantPoll(sessionId);
  }

  private scheduleAssistantPoll(sessionId: string): void {
    this.pollTimer = setTimeout(() => {
      void this.pollForAssistantCompletion(sessionId);
    }, ChatComponent.POLL_INTERVAL_MS);
  }

  private async pollForAssistantCompletion(sessionId: string): Promise<void> {
    if (!this.awaitingAssistant() || this.activeSession()?.id !== sessionId) {
      this.clearAssistantPollTimer();
      return;
    }
    try {
      const detail = await firstValueFrom(this.api.getConversation(sessionId));
      const newMessages = detail.messages.slice(this.baselineMessageCount);
      const completed = newMessages.some(
        (m) => m.role === 'ASSISTANT' || m.role === 'ERROR'
      );
      if (completed) {
        this.activeSession.set(detail);
        this.streamingReply.set('');
        this.awaitingAssistant.set(false);
        this.clearAssistantPollTimer();
        return;
      }
    } catch {
      // Transient poll failure; continue until timeout
    }

    if (Date.now() - this.pollStartedAt >= ChatComponent.POLL_TIMEOUT_MS) {
      this.awaitingAssistant.set(false);
      this.streamingReply.set('');
      this.clearAssistantPollTimer();
      this.error.set('Timed out waiting for assistant reply; select the session again to refresh.');
      return;
    }

    if (this.awaitingAssistant() && this.activeSession()?.id === sessionId) {
      this.scheduleAssistantPoll(sessionId);
    }
  }

  private clearAssistantPollTimer(): void {
    if (this.pollTimer !== null) {
      clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  }

  private async loadSessions(environmentId: string): Promise<void> {
    this.loadingSessions.set(true);
    this.error.set(null);
    try {
      const list = await firstValueFrom(this.api.listConversations(environmentId));
      this.sessions.set(list);
      if (list.length > 0) {
        await this.selectSession(list[0].id);
      }
    } catch (err: unknown) {
      this.error.set(toApiErrorMessage(err, 'Unable to load conversation sessions.'));
    } finally {
      this.loadingSessions.set(false);
    }
  }
}

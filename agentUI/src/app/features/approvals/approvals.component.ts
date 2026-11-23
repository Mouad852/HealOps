import { DatePipe, KeyValuePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import {
  ApprovalDecisionType,
  ApprovalQueueItemResponse,
} from '../../core/api/api.models';
import { generateCorrelationId } from '../../core/api/api.interceptor';
import { HealopsApiClient } from '../../core/api/healops-api.client';
import { ApiError, toApiErrorMessage } from '../../core/api/problem-detail';
import { DashboardEventsService } from '../../core/events/dashboard-events.service';
import { AuthStore } from '../../core/state/auth.store';
import { EnvironmentStore } from '../../core/state/environment.store';
import { StatusBadgeComponent } from '../../shared/status-badge/status-badge.component';

interface PendingDecisionPrompt {
  readonly item: ApprovalQueueItemResponse;
  readonly decision: ApprovalDecisionType;
  readonly idempotencyKey: string;
}

@Component({
  selector: 'app-approvals',
  standalone: true,
  imports: [DatePipe, FormsModule, KeyValuePipe, RouterLink, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="d-flex flex-column gap-3" aria-label="Remediation approval queue">
      <div class="d-flex flex-wrap justify-content-between align-items-center gap-2">
        <div>
          <h1 class="h3 mb-1 text-white">Remediation Approval Queue</h1>
          <p class="text-secondary small mb-0">
            Approving advances the server-side workflow with policy and audit enforcement. Environment:
            <strong class="text-info">{{ envStore.selectedEnvironmentId() }}</strong>.
          </p>
        </div>

        <div class="d-flex align-items-center gap-2">
          <div class="btn-group btn-group-sm" role="group" aria-label="Approval status filter">
            <button
              type="button"
              class="btn"
              [class.btn-info]="statusFilter() === 'PENDING'"
              [class.btn-outline-secondary]="statusFilter() !== 'PENDING'"
              (click)="statusFilter.set('PENDING')"
            >
              Pending
            </button>
            <button
              type="button"
              class="btn"
              [class.btn-info]="statusFilter() === 'ALL'"
              [class.btn-outline-secondary]="statusFilter() !== 'ALL'"
              (click)="statusFilter.set('ALL')"
            >
              All History
            </button>
          </div>
          <button
            type="button"
            class="btn btn-sm btn-outline-info"
            [disabled]="loading()"
            (click)="reload()"
          >
            <i class="bi bi-arrow-clockwise me-1" aria-hidden="true"></i>Refresh
          </button>
        </div>
      </div>

      @if (!authStore.canApprove()) {
        <div class="alert alert-warning py-2 small mb-0" role="note">
          <i class="bi bi-shield-lock-fill me-2" aria-hidden="true"></i>
          Read-only view: your role (<strong>{{ authStore.roleLabel() }}</strong>) does not have
          <code>incident:approve</code> authority. Approval controls are disabled.
        </div>
      }

      @if (conflictNotice(); as conflict) {
        <div class="alert alert-warning d-flex justify-content-between align-items-center py-2 small mb-0" role="alert">
          <span><i class="bi bi-exclamation-triangle-fill me-2" aria-hidden="true"></i>{{ conflict }}</span>
          <button type="button" class="btn-close btn-close-white btn-sm" aria-label="Dismiss conflict notice" (click)="conflictNotice.set(null)"></button>
        </div>
      }

      @if (feedbackMessage(); as feedback) {
        <div class="alert alert-success d-flex justify-content-between align-items-center py-2 small mb-0" role="status">
          <span><i class="bi bi-check-circle-fill me-2" aria-hidden="true"></i>{{ feedback }}</span>
          <button type="button" class="btn-close btn-close-white btn-sm" aria-label="Dismiss status message" (click)="feedbackMessage.set(null)"></button>
        </div>
      }

      @if (error(); as err) {
        <div class="alert alert-danger d-flex justify-content-between align-items-center mb-0" role="alert">
          <span>{{ err }}</span>
          <button type="button" class="btn btn-sm btn-outline-light" (click)="reload()">Retry</button>
        </div>
      }

      @if (loading() && items().length === 0) {
        <div class="text-center py-5" role="status">
          <div class="spinner-border text-info" aria-hidden="true"></div>
          <p class="text-secondary small mt-2 mb-0">Loading approval queue&hellip;</p>
        </div>
      } @else if (items().length === 0) {
        <div class="card healops-surface text-center py-5">
          <div class="card-body">
            <i class="bi bi-shield-check fs-1 text-success d-block mb-2" aria-hidden="true"></i>
            <h2 class="h5 text-white">No {{ statusFilter() === 'PENDING' ? 'Pending' : '' }} Approvals</h2>
            <p class="text-secondary small mb-0">
              Proposed allowlisted remediation actions requiring operator confirmation will appear here.
            </p>
          </div>
        </div>
      } @else {
        <div class="d-flex flex-column gap-3">
          @for (item of items(); track item.actionId) {
            <article class="card healops-surface">
              <div class="card-body d-flex flex-column gap-3">
                <div class="d-flex flex-wrap justify-content-between align-items-start gap-2">
                  <div>
                    <div class="d-flex flex-wrap align-items-center gap-2 mb-1">
                      <app-status-badge [value]="item.riskLevel" prefix="Risk level" />
                      <app-status-badge [value]="item.status" prefix="Approval status" />
                      <app-status-badge [value]="item.incidentStatus" prefix="Incident status" />
                      <span class="badge text-bg-secondary font-monospace">v{{ item.version }}</span>
                    </div>
                    <h2 class="h5 mb-0 text-white">
                      {{ item.actionType }} &mdash; <span class="text-info">{{ item.service }}</span>
                    </h2>
                    <div class="small text-secondary mt-1">
                      Incident
                      <a class="link-info font-monospace text-decoration-none" [routerLink]="['/incidents', item.incidentId]">
                        {{ item.incidentId.slice(0, 8) }}
                      </a>
                      &bull; Environment: <strong>{{ item.environmentId }}</strong>
                      &bull; Proposed <time [attr.datetime]="item.createdAt">{{ item.createdAt | date: 'medium' }}</time>
                    </div>
                  </div>

                  @if (item.status === 'PROPOSED') {
                    <div class="d-flex gap-2">
                      <button
                        type="button"
                        class="btn btn-sm btn-success"
                        [disabled]="!authStore.canApprove() || isPending(item.actionId)"
                        (click)="openConfirmation(item, 'APPROVED')"
                      >
                        <i class="bi bi-check-lg me-1" aria-hidden="true"></i>Approve
                      </button>
                      <button
                        type="button"
                        class="btn btn-sm btn-outline-danger"
                        [disabled]="!authStore.canApprove() || isPending(item.actionId)"
                        (click)="openConfirmation(item, 'REJECTED')"
                      >
                        <i class="bi bi-x-lg me-1" aria-hidden="true"></i>Reject
                      </button>
                    </div>
                  }
                </div>

                <div class="row g-3 small">
                  <div class="col-md-7">
                    <div class="text-secondary text-uppercase fw-semibold mb-1">Equivalent CLI Command Preview</div>
                    <pre class="healops-pre text-info mb-0">{{ item.commandPreview }}</pre>
                  </div>
                  <div class="col-md-5 d-flex flex-column gap-2">
                    <div>
                      <div class="text-secondary text-uppercase fw-semibold mb-1">Target Parameters</div>
                      <div class="d-flex flex-wrap gap-1">
                        @for (arg of item.arguments | keyvalue; track arg.key) {
                          <span class="healops-kv-chip">
                            <strong>{{ arg.key }}</strong> {{ arg.value }}
                          </span>
                        }
                      </div>
                    </div>
                    <div>
                      <div class="text-secondary text-uppercase fw-semibold mb-1">Rollback Safety Strategy</div>
                      <div class="text-light">
                        {{ item.rollbackStrategy || 'No automated rollback available; failed verification marks incident failed.' }}
                      </div>
                    </div>
                  </div>
                </div>

                @if (item.latestDecision; as dec) {
                  <div class="pt-2 border-top border-secondary-subtle small text-secondary">
                    Latest decision: <strong class="text-white">{{ dec.decision }}</strong> by
                    <strong class="text-light">{{ dec.actor }}</strong> at
                    <time [attr.datetime]="dec.decidedAt">{{ dec.decidedAt | date: 'medium' }}</time>
                    (policy {{ dec.policyVersion }})
                    @if (dec.reason) {
                      <span>&mdash; "{{ dec.reason }}"</span>
                    }
                  </div>
                }
              </div>
            </article>
          }
        </div>
      }
    </section>

    <dialog
      #confirmDialog
      class="healops-dialog"
      aria-labelledby="approval-dialog-title"
      (cancel)="onDialogCancel($event)"
      (click)="onBackdropClick($event)"
    >
      @if (activePrompt(); as prompt) {
        <form class="p-4 d-flex flex-column gap-3" (click)="$event.stopPropagation()" (ngSubmit)="confirmDecision()">
          <div class="d-flex justify-content-between align-items-start gap-2 border-bottom border-secondary pb-2">
            <div>
              <h2 id="approval-dialog-title" class="h5 mb-1 text-white">
                Confirm {{ prompt.decision === 'APPROVED' ? 'Approval' : 'Rejection' }}
              </h2>
              <div class="small text-secondary">
                {{ prompt.item.actionType }} on <strong class="text-info">{{ prompt.item.service }}</strong>
                (v{{ prompt.item.version }})
              </div>
            </div>
            <button
              type="button"
              class="btn-close btn-close-white"
              aria-label="Cancel decision"
              [disabled]="submitting()"
              (click)="closeConfirmation()"
            ></button>
          </div>

          <div class="small">
            <div class="d-flex align-items-center gap-2 mb-2">
              <app-status-badge [value]="prompt.item.riskLevel" prefix="Risk" />
              <span class="font-monospace text-secondary">Idempotency-Key: {{ prompt.idempotencyKey.slice(0, 16) }}&hellip;</span>
            </div>
            <pre class="healops-pre mb-2">{{ prompt.item.commandPreview }}</pre>
            <div class="text-secondary">
              Rollback strategy: <span class="text-light">{{ prompt.item.rollbackStrategy || 'Non-reversible' }}</span>
            </div>
          </div>

          <div>
            <label for="decisionReason" class="form-label small text-secondary mb-1">
              Operator Reason (recorded in immutable audit log)
            </label>
            <textarea
              #reasonInput
              id="decisionReason"
              name="decisionReason"
              rows="2"
              class="form-control form-control-sm bg-dark text-white border-secondary"
              [ngModel]="decisionReason()"
              (ngModelChange)="decisionReason.set($event)"
              placeholder="Briefly document why you are approving or rejecting this remediation..."
            ></textarea>
          </div>

          <div class="d-flex justify-content-end gap-2 pt-2 border-top border-secondary">
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary"
              [disabled]="submitting()"
              (click)="closeConfirmation()"
            >
              Cancel
            </button>
            <button
              type="submit"
              class="btn btn-sm"
              [class.btn-success]="prompt.decision === 'APPROVED'"
              [class.btn-danger]="prompt.decision === 'REJECTED'"
              [disabled]="submitting()"
            >
              @if (submitting()) {
                <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
              }
              Confirm {{ prompt.decision === 'APPROVED' ? 'Approve' : 'Reject' }}
            </button>
          </div>
        </form>
      }
    </dialog>
  `,
})
export class ApprovalsComponent {
  readonly envStore = inject(EnvironmentStore);
  readonly authStore = inject(AuthStore);
  private readonly api = inject(HealopsApiClient);
  private readonly sse = inject(DashboardEventsService);
  private readonly destroyRef = inject(DestroyRef);

  readonly statusFilter = signal<'PENDING' | 'ALL'>('PENDING');
  readonly items = signal<readonly ApprovalQueueItemResponse[]>([]);
  readonly loading = signal<boolean>(false);
  readonly error = signal<string | null>(null);
  readonly conflictNotice = signal<string | null>(null);
  readonly feedbackMessage = signal<string | null>(null);
  readonly activePrompt = signal<PendingDecisionPrompt | null>(null);
  readonly decisionReason = signal<string>('');
  readonly submitting = signal<boolean>(false);
  readonly pendingActionIds = signal<ReadonlySet<string>>(new Set());

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('confirmDialog');
  private readonly reasonInputRef = viewChild<ElementRef<HTMLTextAreaElement>>('reasonInput');
  private previouslyFocused: HTMLElement | null = null;

  constructor() {
    effect(() => {
      const envId = this.envStore.selectedEnvironmentId();
      const status = this.statusFilter();
      void this.loadApprovals(envId, status);
    });

    effect(() => {
      const prompt = this.activePrompt();
      const dialog = this.dialogRef()?.nativeElement;
      if (!dialog) {
        return;
      }
      if (prompt) {
        if (!dialog.open) {
          this.previouslyFocused =
            typeof document !== 'undefined' && document.activeElement instanceof HTMLElement
              ? document.activeElement
              : null;
          if (typeof dialog.showModal === 'function') {
            dialog.showModal();
          } else {
            dialog.setAttribute('open', '');
          }
          queueMicrotask(() => this.reasonInputRef()?.nativeElement.focus());
        }
      } else if (dialog.open) {
        dialog.close();
        this.previouslyFocused?.focus();
        this.previouslyFocused = null;
      }
    });

    this.sse
      .ofType('approval.created', 'approval.updated')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((envelope) => {
        if (envelope.environmentId === this.envStore.selectedEnvironmentId()) {
          void this.loadApprovals(this.envStore.selectedEnvironmentId(), this.statusFilter(), true);
        }
      });
  }

  isPending(actionId: string): boolean {
    return this.pendingActionIds().has(actionId);
  }

  reload(): void {
    void this.loadApprovals(this.envStore.selectedEnvironmentId(), this.statusFilter());
  }

  openConfirmation(item: ApprovalQueueItemResponse, decision: ApprovalDecisionType): void {
    if (!this.authStore.canApprove() || this.isPending(item.actionId)) {
      return;
    }
    this.conflictNotice.set(null);
    this.feedbackMessage.set(null);
    this.decisionReason.set('');
    this.activePrompt.set({
      item,
      decision,
      idempotencyKey: `approval-${item.actionId}-${generateCorrelationId()}`,
    });
  }

  closeConfirmation(): void {
    if (this.submitting()) {
      return;
    }
    this.activePrompt.set(null);
  }

  onDialogCancel(event: Event): void {
    event.preventDefault();
    this.closeConfirmation();
  }

  onBackdropClick(event: MouseEvent): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && event.target === dialog) {
      this.closeConfirmation();
    }
  }

  async confirmDecision(): Promise<void> {
    const prompt = this.activePrompt();
    if (!prompt || this.submitting() || this.isPending(prompt.item.actionId)) {
      return;
    }

    const actionId = prompt.item.actionId;
    this.submitting.set(true);
    this.pendingActionIds.update((prev) => new Set([...prev, actionId]));
    this.error.set(null);

    try {
      const response = await firstValueFrom(
        this.api.submitApprovalDecision(actionId, {
          decision: prompt.decision,
          reason: this.decisionReason().trim(),
          expectedVersion: prompt.item.version,
          idempotencyKey: prompt.idempotencyKey,
        })
      );
      this.activePrompt.set(null);
      this.feedbackMessage.set(
        `Decision ${response.decision} recorded for ${prompt.item.actionType} (policy ${response.policyVersion}).`
      );
      await this.loadApprovals(this.envStore.selectedEnvironmentId(), this.statusFilter(), true);
    } catch (err: unknown) {
      this.activePrompt.set(null);
      if (err instanceof ApiError && err.isConflict) {
        this.conflictNotice.set(
          `Conflict (409): ${err.problem.detail}. The approval queue has been refreshed with the latest version.`
        );
        await this.loadApprovals(this.envStore.selectedEnvironmentId(), this.statusFilter(), true);
      } else {
        this.error.set(toApiErrorMessage(err, 'Failed to submit approval decision.'));
      }
    } finally {
      this.submitting.set(false);
      this.pendingActionIds.update((prev) => {
        const next = new Set(prev);
        next.delete(actionId);
        return next;
      });
    }
  }

  private async loadApprovals(
    environmentId: string,
    status: 'PENDING' | 'ALL',
    silent = false
  ): Promise<void> {
    if (!environmentId) {
      return;
    }
    if (!silent) {
      this.loading.set(true);
    }
    this.error.set(null);
    try {
      const list = await firstValueFrom(this.api.getApprovals(environmentId, status));
      this.items.set(list);
    } catch (err: unknown) {
      this.error.set(toApiErrorMessage(err, 'Unable to load approval queue.'));
    } finally {
      if (!silent) {
        this.loading.set(false);
      }
    }
  }
}

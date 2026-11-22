import { DatePipe, JsonPipe } from '@angular/common';
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
import { ActivatedRoute } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import {
  ActionType,
  IncidentResponse,
  RunbookDetailResponse,
  RunbookStatus,
  RunbookStepRequest,
} from '../../core/api/api.models';
import { HealopsApiClient } from '../../core/api/healops-api.client';
import { toApiErrorMessage } from '../../core/api/problem-detail';
import { AuthStore } from '../../core/state/auth.store';
import { EnvironmentStore } from '../../core/state/environment.store';
import { StatusBadgeComponent } from '../../shared/status-badge/status-badge.component';

interface EditableStep {
  actionType: ActionType;
  targetArgKey: string;
  targetArgValue: string;
  extraArgKey: string;
  extraArgValue: string;
  description: string;
}

@Component({
  selector: 'app-runbooks',
  standalone: true,
  imports: [DatePipe, FormsModule, JsonPipe, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="d-flex flex-column gap-4" aria-label="Runbooks management">
      <div class="d-flex flex-wrap justify-content-between align-items-center gap-2">
        <div>
          <h1 class="h3 mb-1 text-white">Automated Runbooks</h1>
          <p class="text-secondary small mb-0">
            Draft reusable remediation playbooks from closed incidents, edit typed steps, and publish with
            <code>runbook:publish</code> governance.
          </p>
        </div>

        <div class="d-flex align-items-center gap-2">
          <label for="runbookStatusFilter" class="visually-hidden">Filter runbooks by status</label>
          <select
            id="runbookStatusFilter"
            class="form-select form-select-sm bg-dark text-white border-secondary w-auto"
            [ngModel]="statusFilter()"
            (ngModelChange)="onStatusFilterChange($event)"
          >
            <option value="">All statuses</option>
            <option value="DRAFT">DRAFT</option>
            <option value="PUBLISHED">PUBLISHED</option>
            <option value="ARCHIVED">ARCHIVED</option>
          </select>
          <button type="button" class="btn btn-sm btn-outline-info" [disabled]="loading()" (click)="reload()">
            <i class="bi bi-arrow-clockwise me-1" aria-hidden="true"></i>Refresh
          </button>
        </div>
      </div>

      @if (notice(); as msg) {
        <div class="alert alert-success py-2 small mb-0" role="status">{{ msg }}</div>
      }
      @if (error(); as err) {
        <div class="alert alert-danger py-2 small mb-0" role="alert">{{ err }}</div>
      }

      <div class="card healops-surface">
        <div class="card-header border-secondary-subtle">
          <h2 class="h6 mb-0 text-white">Draft Runbook from Closed Incident</h2>
        </div>
        <div class="card-body">
          @if (!authStore.canWrite()) {
            <p class="text-warning small mb-0">
              <i class="bi bi-lock-fill me-1" aria-hidden="true"></i>
              Drafting runbooks requires <code>incident:write</code> authority.
            </p>
          } @else if (closedIncidents().length === 0) {
            <p class="text-secondary small mb-0">
              No closed incidents found in <strong>{{ envStore.selectedEnvironmentId() }}</strong> yet.
            </p>
          } @else {
            <form class="row g-2 align-items-end" (ngSubmit)="draftFromClosedIncident()">
              <div class="col-md-5">
                <label for="closedIncidentSelect" class="form-label small text-secondary mb-1">Closed Incident</label>
                <select
                  id="closedIncidentSelect"
                  name="closedIncidentId"
                  class="form-select form-select-sm bg-dark text-white border-secondary"
                  [ngModel]="selectedClosedIncidentId()"
                  (ngModelChange)="selectedClosedIncidentId.set($event)"
                >
                  @for (inc of closedIncidents(); track inc.id) {
                    <option [value]="inc.id">
                      {{ inc.service }} ({{ inc.severity }}) &mdash; {{ inc.id.slice(0, 8) }}
                    </option>
                  }
                </select>
              </div>
              <div class="col-md-5">
                <label for="draftNameInput" class="form-label small text-secondary mb-1">Runbook Name (optional)</label>
                <input
                  id="draftNameInput"
                  name="draftName"
                  type="text"
                  class="form-control form-control-sm bg-dark text-white border-secondary"
                  placeholder="e.g. Checkout API Restart Playbook"
                  [ngModel]="draftName()"
                  (ngModelChange)="draftName.set($event)"
                />
              </div>
              <div class="col-md-2">
                <button
                  type="submit"
                  class="btn btn-sm btn-info w-100 fw-semibold"
                  [disabled]="drafting() || !selectedClosedIncidentId()"
                >
                  Create Draft
                </button>
              </div>
            </form>
          }
        </div>
      </div>

      <div class="row g-4">
        <div class="col-lg-4">
          <div class="card healops-surface h-100">
            <div class="card-header border-secondary-subtle">
              <h2 class="h6 mb-0 text-white">Runbook Catalog ({{ runbooks().length }})</h2>
            </div>
            <div class="list-group list-group-flush">
              @if (loading() && runbooks().length === 0) {
                <div class="p-3 small text-secondary" role="status">Loading runbooks&hellip;</div>
              } @else if (runbooks().length === 0) {
                <div class="p-3 small text-secondary">No runbooks available.</div>
              } @else {
                @for (rb of runbooks(); track rb.id) {
                  <button
                    type="button"
                    class="list-group-item list-group-item-action bg-transparent text-light border-secondary-subtle text-start"
                    [class.active]="selectedRunbook()?.id === rb.id"
                    (click)="selectRunbook(rb)"
                  >
                    <div class="d-flex justify-content-between align-items-center gap-2 mb-1">
                      <strong class="small text-truncate">{{ rb.name }}</strong>
                      <app-status-badge [value]="rb.status" prefix="Runbook status" />
                    </div>
                    <div class="small text-secondary">
                      v{{ rb.currentVersion }} &bull; Updated
                      <time [attr.datetime]="rb.updatedAt">{{ rb.updatedAt | date: 'short' }}</time>
                    </div>
                  </button>
                }
              }
            </div>
          </div>
        </div>

        <div class="col-lg-8">
          @if (selectedRunbook(); as rb) {
            <div class="card healops-surface">
              <div class="card-header border-secondary-subtle d-flex flex-wrap justify-content-between align-items-center gap-2">
                <div>
                  <div class="d-flex align-items-center gap-2">
                    <h2 class="h5 mb-0 text-white">{{ rb.name }}</h2>
                    <app-status-badge [value]="rb.status" prefix="Status" />
                    <span class="badge text-bg-secondary font-monospace">v{{ rb.currentVersion }}</span>
                  </div>
                  @if (rb.latestVersion?.publishedBy; as pubBy) {
                    <div class="small text-secondary mt-1">
                      Published by <strong class="text-light">{{ pubBy }}</strong>
                    </div>
                  }
                </div>

                <div class="d-flex gap-2">
                  @if (authStore.canPublishRunbook()) {
                    <button
                      type="button"
                      class="btn btn-sm btn-success"
                      [disabled]="publishing() || rb.status === 'PUBLISHED'"
                      (click)="publishSelected(rb)"
                    >
                      <i class="bi bi-check2-all me-1" aria-hidden="true"></i>
                      {{ rb.status === 'PUBLISHED' ? 'Published' : 'Publish Runbook' }}
                    </button>
                  } @else {
                    <span class="badge text-bg-secondary align-self-center">
                      <i class="bi bi-lock-fill me-1" aria-hidden="true"></i>Requires runbook:publish
                    </span>
                  }
                </div>
              </div>

              <div class="card-body d-flex flex-column gap-3">
                <div>
                  <label for="editRunbookName" class="form-label small text-secondary mb-1">Runbook Title</label>
                  <input
                    id="editRunbookName"
                    type="text"
                    class="form-control form-control-sm bg-dark text-white border-secondary"
                    [ngModel]="editName()"
                    (ngModelChange)="editName.set($event)"
                    [disabled]="!authStore.canWrite()"
                  />
                </div>

                <div>
                  <div class="d-flex justify-content-between align-items-center mb-2">
                    <h3 class="h6 mb-0 text-white">Typed Remediation Steps</h3>
                    @if (authStore.canWrite()) {
                      <button type="button" class="btn btn-sm btn-outline-info" (click)="addStep()">
                        <i class="bi bi-plus-lg me-1" aria-hidden="true"></i>Add Step
                      </button>
                    }
                  </div>

                  <div class="d-flex flex-column gap-2">
                    @for (step of editableSteps(); track $index) {
                      <div class="p-3 rounded border border-secondary-subtle bg-dark d-flex flex-column gap-2">
                        <div class="d-flex justify-content-between align-items-center">
                          <span class="badge text-bg-secondary">Step {{ $index + 1 }}</span>
                          @if (authStore.canWrite() && editableSteps().length > 1) {
                            <button
                              type="button"
                              class="btn btn-sm btn-outline-danger py-0"
                              (click)="removeStep($index)"
                            >
                              Remove
                            </button>
                          }
                        </div>

                        <div class="row g-2">
                          <div class="col-md-4">
                            <label [attr.for]="'stepAction-' + $index" class="form-label small text-secondary mb-1">Action Type</label>
                            <select
                              [id]="'stepAction-' + $index"
                              class="form-select form-select-sm bg-dark text-white border-secondary"
                              [ngModel]="step.actionType"
                              (ngModelChange)="updateStepField($index, 'actionType', $event)"
                              [disabled]="!authStore.canWrite()"
                            >
                              @for (action of actionTypes; track action) {
                                <option [value]="action">{{ action }}</option>
                              }
                            </select>
                          </div>
                          <div class="col-md-4">
                            <label [attr.for]="'stepKey-' + $index" class="form-label small text-secondary mb-1">Primary Arg Key</label>
                            <input
                              [id]="'stepKey-' + $index"
                              type="text"
                              class="form-control form-control-sm bg-dark text-white border-secondary font-monospace"
                              [ngModel]="step.targetArgKey"
                              (ngModelChange)="updateStepField($index, 'targetArgKey', $event)"
                              placeholder="e.g. containerName / deployment"
                              [disabled]="!authStore.canWrite()"
                            />
                          </div>
                          <div class="col-md-4">
                            <label [attr.for]="'stepVal-' + $index" class="form-label small text-secondary mb-1">Primary Arg Value</label>
                            <input
                              [id]="'stepVal-' + $index"
                              type="text"
                              class="form-control form-control-sm bg-dark text-white border-secondary font-monospace"
                              [ngModel]="step.targetArgValue"
                              (ngModelChange)="updateStepField($index, 'targetArgValue', $event)"
                              placeholder="e.g. checkout-api"
                              [disabled]="!authStore.canWrite()"
                            />
                          </div>
                        </div>

                        <div>
                          <label [attr.for]="'stepDesc-' + $index" class="form-label small text-secondary mb-1">Description</label>
                          <input
                            [id]="'stepDesc-' + $index"
                            type="text"
                            class="form-control form-control-sm bg-dark text-white border-secondary"
                            [ngModel]="step.description"
                            (ngModelChange)="updateStepField($index, 'description', $event)"
                            [disabled]="!authStore.canWrite()"
                          />
                        </div>
                      </div>
                    }
                  </div>
                </div>

                @if (authStore.canWrite()) {
                  <div class="d-flex justify-content-end">
                    <button
                      type="button"
                      class="btn btn-sm btn-info fw-semibold"
                      [disabled]="saving() || editableSteps().length === 0"
                      (click)="saveDraft(rb)"
                    >
                      @if (saving()) {
                        <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
                      }
                      Save New Draft Version
                    </button>
                  </div>
                }

                <details class="small">
                  <summary class="text-info" role="button">Match Criteria &amp; Version History ({{ rb.versions.length }})</summary>
                  <pre class="healops-pre mt-2 mb-0">{{ rb.matchCriteria | json }}</pre>
                </details>
              </div>
            </div>
          } @else {
            <div class="card healops-surface text-center py-5">
              <div class="card-body text-secondary small">
                Select a runbook from the catalog or draft one from a closed incident.
              </div>
            </div>
          }
        </div>
      </div>
    </section>
  `,
})
export class RunbooksComponent {
  readonly authStore = inject(AuthStore);
  readonly envStore = inject(EnvironmentStore);
  private readonly api = inject(HealopsApiClient);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  readonly actionTypes: readonly ActionType[] = [
    'RESTART_CONTAINER',
    'RESTART_K8S_WORKLOAD',
    'SCALE_K8S_DEPLOYMENT',
    'ROLLBACK_K8S_DEPLOYMENT',
    'RESTART_OPENSTACK_SERVICE',
    'NO_ACTION',
  ];

  readonly statusFilter = signal<RunbookStatus | ''>('');
  readonly runbooks = signal<readonly RunbookDetailResponse[]>([]);
  readonly selectedRunbook = signal<RunbookDetailResponse | null>(null);
  readonly closedIncidents = signal<readonly IncidentResponse[]>([]);
  readonly selectedClosedIncidentId = signal<string>('');
  readonly draftName = signal<string>('');

  readonly editName = signal<string>('');
  readonly editableSteps = signal<readonly EditableStep[]>([]);

  readonly loading = signal<boolean>(false);
  readonly drafting = signal<boolean>(false);
  readonly saving = signal<boolean>(false);
  readonly publishing = signal<boolean>(false);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);

  private pendingSelectRunbookId: string | null = null;

  constructor() {
    this.route.queryParamMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => {
        this.pendingSelectRunbookId = params.get('runbookId');
      });

    effect(() => {
      const status = this.statusFilter();
      void this.loadRunbooks(status);
    });

    effect(() => {
      const envId = this.envStore.selectedEnvironmentId();
      if (envId) {
        void this.loadClosedIncidents(envId);
      }
    });
  }

  onStatusFilterChange(value: string): void {
    this.statusFilter.set(value as RunbookStatus | '');
  }

  reload(): void {
    void this.loadRunbooks(this.statusFilter());
    void this.loadClosedIncidents(this.envStore.selectedEnvironmentId());
  }

  selectRunbook(rb: RunbookDetailResponse): void {
    this.selectedRunbook.set(rb);
    this.editName.set(rb.name);
    const steps = rb.latestVersion?.typedSteps ?? [];
    if (steps.length === 0) {
      this.editableSteps.set([this.defaultStep()]);
      return;
    }
    this.editableSteps.set(
      steps.map((step) => {
        const entries = Object.entries(step.arguments);
        return {
          actionType: step.actionType,
          targetArgKey: entries[0]?.[0] ?? 'containerName',
          targetArgValue: entries[0]?.[1] !== undefined ? String(entries[0][1]) : '',
          extraArgKey: entries[1]?.[0] ?? '',
          extraArgValue: entries[1]?.[1] !== undefined ? String(entries[1][1]) : '',
          description: step.description ?? '',
        };
      })
    );
  }

  addStep(): void {
    this.editableSteps.update((list) => [...list, this.defaultStep()]);
  }

  removeStep(index: number): void {
    this.editableSteps.update((list) => list.filter((_, i) => i !== index));
  }

  updateStepField<K extends keyof EditableStep>(index: number, field: K, value: EditableStep[K]): void {
    this.editableSteps.update((list) =>
      list.map((step, i) => (i === index ? { ...step, [field]: value } : step))
    );
  }

  async draftFromClosedIncident(): Promise<void> {
    const incidentId = this.selectedClosedIncidentId();
    if (!incidentId || this.drafting()) {
      return;
    }
    this.drafting.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      const drafted = await firstValueFrom(
        this.api.draftRunbookFromIncident(incidentId, {
          name: this.draftName().trim() || undefined,
        })
      );
      this.draftName.set('');
      this.notice.set(`Draft runbook "${drafted.name}" created.`);
      this.runbooks.update((list) => [drafted, ...list]);
      this.selectRunbook(drafted);
    } catch (err: unknown) {
      this.error.set(toApiErrorMessage(err, 'Failed to draft runbook from closed incident.'));
    } finally {
      this.drafting.set(false);
    }
  }

  async saveDraft(rb: RunbookDetailResponse): Promise<void> {
    if (this.saving()) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.notice.set(null);

    const typedSteps: RunbookStepRequest[] = this.editableSteps().map((step) => {
      const args: Record<string, unknown> = {};
      if (step.targetArgKey.trim() && step.targetArgValue.trim()) {
        args[step.targetArgKey.trim()] = step.targetArgValue.trim();
      }
      if (step.extraArgKey.trim() && step.extraArgValue.trim()) {
        args[step.extraArgKey.trim()] = step.extraArgValue.trim();
      }
      return {
        actionType: step.actionType,
        arguments: args,
        description: step.description.trim(),
      };
    });

    try {
      const updated = await firstValueFrom(
        this.api.updateRunbook(rb.id, {
          name: this.editName().trim() || rb.name,
          matchCriteria: rb.matchCriteria,
          typedSteps,
          policyRequirements: rb.latestVersion?.policyRequirements ?? {},
        })
      );
      this.replaceRunbookInList(updated);
      this.selectRunbook(updated);
      this.notice.set(`Saved draft version v${updated.currentVersion}.`);
    } catch (err: unknown) {
      this.error.set(toApiErrorMessage(err, 'Unable to update runbook draft.'));
    } finally {
      this.saving.set(false);
    }
  }

  async publishSelected(rb: RunbookDetailResponse): Promise<void> {
    if (!this.authStore.canPublishRunbook() || this.publishing()) {
      return;
    }
    this.publishing.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      const published = await firstValueFrom(this.api.publishRunbook(rb.id));
      this.replaceRunbookInList(published);
      this.selectRunbook(published);
      this.notice.set(`Published "${published.name}" v${published.currentVersion}.`);
    } catch (err: unknown) {
      this.error.set(toApiErrorMessage(err, 'Unable to publish runbook.'));
    } finally {
      this.publishing.set(false);
    }
  }

  private replaceRunbookInList(updated: RunbookDetailResponse): void {
    this.runbooks.update((list) =>
      list.some((item) => item.id === updated.id)
        ? list.map((item) => (item.id === updated.id ? updated : item))
        : [updated, ...list]
    );
  }

  private defaultStep(): EditableStep {
    return {
      actionType: 'RESTART_CONTAINER',
      targetArgKey: 'containerName',
      targetArgValue: '',
      extraArgKey: '',
      extraArgValue: '',
      description: 'Restart degraded target container',
    };
  }

  private async loadRunbooks(status: RunbookStatus | ''): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const list = await firstValueFrom(this.api.listRunbooks(status));
      this.runbooks.set(list);
      const targetId = this.pendingSelectRunbookId ?? this.selectedRunbook()?.id ?? null;
      const match = targetId ? list.find((r) => r.id === targetId) : list[0];
      if (match) {
        this.selectRunbook(match);
        this.pendingSelectRunbookId = null;
      } else {
        this.selectedRunbook.set(null);
      }
    } catch (err: unknown) {
      this.error.set(toApiErrorMessage(err, 'Unable to load runbooks.'));
    } finally {
      this.loading.set(false);
    }
  }

  private async loadClosedIncidents(environmentId: string): Promise<void> {
    try {
      const page = await firstValueFrom(
        this.api.getIncidentsPage({ environmentId, status: 'CLOSED', limit: 20 })
      );
      this.closedIncidents.set(page.items);
      if (page.items.length > 0) {
        this.selectedClosedIncidentId.set(page.items[0].id);
      } else {
        this.selectedClosedIncidentId.set('');
      }
    } catch {
      this.closedIncidents.set([]);
    }
  }
}

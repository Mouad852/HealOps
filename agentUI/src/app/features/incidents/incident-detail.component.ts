import { DatePipe, DecimalPipe, JsonPipe, KeyValuePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import {
  DiagnosisResponse,
  EvidenceItemResponse,
  EvidenceType,
  HealthComparisonResponse,
  IncidentEventResponse,
  IncidentReportResponse,
  IncidentResponse,
  ProposedActionResponse,
  RunbookSuggestionResponse,
  TERMINAL_INCIDENT_STATUSES,
  ToolExecutionResponse,
} from '../../core/api/api.models';
import { HealopsApiClient } from '../../core/api/healops-api.client';
import { ApiError, toApiErrorMessage } from '../../core/api/problem-detail';
import { DashboardEventsService } from '../../core/events/dashboard-events.service';
import { AuthStore } from '../../core/state/auth.store';
import { EvidenceDialogComponent } from '../../shared/evidence-dialog/evidence-dialog.component';
import { IncidentTimelineComponent } from '../../shared/incident-timeline/incident-timeline.component';
import { StatusBadgeComponent } from '../../shared/status-badge/status-badge.component';
import { ToolProgressComponent } from '../../shared/tool-progress/tool-progress.component';

@Component({
  selector: 'app-incident-detail',
  standalone: true,
  imports: [
    DatePipe,
    DecimalPipe,
    FormsModule,
    JsonPipe,
    KeyValuePipe,
    RouterLink,
    EvidenceDialogComponent,
    IncidentTimelineComponent,
    StatusBadgeComponent,
    ToolProgressComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="d-flex flex-column gap-4" aria-label="Incident detail view">
      <div class="d-flex flex-wrap justify-content-between align-items-center gap-2">
        <div class="d-flex align-items-center gap-2">
          <a class="btn btn-sm btn-outline-secondary" routerLink="/incidents">
            <i class="bi bi-arrow-left me-1" aria-hidden="true"></i>Incidents
          </a>
          <h1 class="h4 mb-0 text-white">Incident Investigation</h1>
        </div>
        <div class="d-flex flex-wrap gap-2">
          @if (incident(); as inc) {
            @if (inc.status === 'FAILED' && authStore.canWrite()) {
              <button
                type="button"
                class="btn btn-sm btn-warning"
                [disabled]="retrying()"
                (click)="retryFailedPhase(inc)"
              >
                <i class="bi bi-arrow-counterclockwise me-1" aria-hidden="true"></i>Retry Failed Phase
              </button>
            }
            @if (inc.status === 'CLOSED' && authStore.canWrite()) {
              <button
                type="button"
                class="btn btn-sm btn-outline-success"
                [disabled]="draftingRunbook()"
                (click)="draftRunbook(inc)"
              >
                <i class="bi bi-journal-plus me-1" aria-hidden="true"></i>Draft Runbook
              </button>
            }
            <a
              class="btn btn-sm btn-outline-info"
              [routerLink]="['/chat']"
              [queryParams]="{ incidentId: inc.id }"
            >
              <i class="bi bi-chat-dots me-1" aria-hidden="true"></i>Ask Copilot
            </a>
          }
          <button
            type="button"
            class="btn btn-sm btn-outline-light"
            [disabled]="loading()"
            (click)="reload()"
          >
            <i class="bi bi-arrow-clockwise me-1" aria-hidden="true"></i>Refresh
          </button>
        </div>
      </div>

      @if (actionNotice(); as notice) {
        <div class="alert alert-info py-2 small mb-0" role="status">{{ notice }}</div>
      }

      @if (error(); as err) {
        <div class="alert alert-danger d-flex justify-content-between align-items-center mb-0" role="alert">
          <span>{{ err }}</span>
          <button type="button" class="btn btn-sm btn-outline-light" (click)="reload()">Retry</button>
        </div>
      }

      @if (loading() && !incident()) {
        <div class="text-center py-5" role="status">
          <div class="spinner-border text-info" aria-hidden="true"></div>
          <p class="text-secondary small mt-2 mb-0">Loading incident state and telemetry&hellip;</p>
        </div>
      } @else {
        @if (incident(); as inc) {
        <div class="card healops-surface">
          <div class="card-body d-flex flex-column gap-3">
            <div class="d-flex flex-wrap justify-content-between align-items-start gap-2">
              <div>
                <div class="d-flex flex-wrap align-items-center gap-2 mb-1">
                  <app-status-badge [value]="inc.severity" prefix="Severity" />
                  <app-status-badge [value]="inc.status" prefix="Status" />
                  <span class="badge text-bg-secondary font-monospace">env: {{ inc.environmentId }}</span>
                  <span class="badge text-bg-dark border border-secondary-subtle font-monospace">v{{ inc.version }}</span>
                </div>
                <h2 class="h5 mb-0 text-white">{{ inc.service }}</h2>
                <div class="small text-secondary font-monospace mt-1">
                  ID: {{ inc.id }} &bull; Fingerprint: {{ inc.fingerprint }}
                </div>
              </div>
              <div class="small text-secondary text-md-end">
                <div>
                  Started: <time [attr.datetime]="inc.startedAt">{{ inc.startedAt | date: 'medium' }}</time>
                </div>
                <div>Source: <strong class="text-light">{{ inc.source }}</strong></div>
                @if (inc.traceId) {
                  <div class="font-monospace">
                    Trace:
                    <a class="link-info text-decoration-none" [routerLink]="['/services']" [queryParams]="{ traceId: inc.traceId }">
                      {{ inc.traceId }}
                    </a>
                  </div>
                }
              </div>
            </div>

            @if ((inc.annotations | keyvalue).length > 0 || (inc.labels | keyvalue).length > 0) {
              <div class="d-flex flex-wrap gap-2 pt-2 border-top border-secondary-subtle small">
                @for (entry of inc.labels | keyvalue; track entry.key) {
                  <span class="badge text-bg-dark border border-secondary-subtle font-monospace fw-normal">
                    {{ entry.key }}={{ entry.value }}
                  </span>
                }
                @for (entry of inc.annotations | keyvalue; track entry.key) {
                  <span class="text-light-emphasis">{{ entry.key }}: {{ entry.value }}</span>
                }
              </div>
            }
          </div>
        </div>

        <div class="row g-4">
          <div class="col-lg-6">
            <div class="card healops-surface h-100">
              <div class="card-header border-secondary-subtle">
                <h2 class="h6 mb-0 text-white">Ordered Incident Timeline</h2>
              </div>
              <div class="card-body">
                <app-incident-timeline [events]="timeline()" />
              </div>
            </div>
          </div>

          <div class="col-lg-6">
            <div class="card healops-surface h-100">
              <div class="card-header border-secondary-subtle">
                <h2 class="h6 mb-0 text-white">Evidence-Backed Diagnosis</h2>
              </div>
              <div class="card-body d-flex flex-column gap-3">
                @if (diagnoses().length === 0) {
                  <p class="text-secondary small mb-0">No diagnosis persisted yet.</p>
                } @else {
                  @for (diag of diagnoses(); track diag.id) {
                    <article class="p-3 rounded border border-secondary-subtle bg-dark d-flex flex-column gap-2">
                      <div class="d-flex flex-wrap justify-content-between align-items-center gap-2">
                        <strong class="text-white">Confidence: {{ diag.confidence * 100 | number: '1.0-0' }}%</strong>
                        <span class="small text-secondary font-monospace">
                          {{ diag.ruleVersion }} / {{ diag.modelVersion }}
                        </span>
                      </div>
                      <p class="mb-0 small text-light">{{ diag.summary }}</p>

                      @if (diag.hypotheses.length > 0) {
                        <ol class="mb-0 ps-3 small d-flex flex-column gap-2">
                          @for (hyp of diag.hypotheses; track hyp.title) {
                            <li>
                              <div class="fw-semibold text-info">
                                {{ hyp.title }}
                                <span class="text-secondary fw-normal">({{ hyp.confidence * 100 | number: '1.0-0' }}%)</span>
                              </div>
                              <div class="text-light-emphasis">{{ hyp.description }}</div>
                              @if (hyp.evidenceIds.length > 0) {
                                <div class="d-flex flex-wrap gap-1 mt-1">
                                  @for (evId of hyp.evidenceIds; track evId) {
                                    <button
                                      type="button"
                                      class="btn btn-outline-info btn-sm py-0 px-2 font-monospace"
                                      (click)="openEvidenceById(evId)"
                                    >
                                      Cite {{ evId.slice(0, 8) }}
                                    </button>
                                  }
                                </div>
                              }
                            </li>
                          }
                        </ol>
                      }
                    </article>
                  }
                }
              </div>
            </div>
          </div>
        </div>

        <div class="row g-4">
          <div class="col-lg-6">
            <div class="card healops-surface h-100">
              <div class="card-header border-secondary-subtle d-flex justify-content-between align-items-center">
                <h2 class="h6 mb-0 text-white">Proposed Remediation Actions</h2>
                <a class="small link-warning text-decoration-none" routerLink="/approvals">Go to Approval Queue</a>
              </div>
              <div class="card-body d-flex flex-column gap-2">
                @if (proposedActions().length === 0) {
                  <p class="text-secondary small mb-0">No remediation actions proposed.</p>
                } @else {
                  @for (action of proposedActions(); track action.id) {
                    <div class="p-3 rounded border border-secondary-subtle bg-dark d-flex flex-column gap-2">
                      <div class="d-flex flex-wrap justify-content-between align-items-center gap-2">
                        <div class="d-flex align-items-center gap-2">
                          <strong class="text-white font-monospace small">{{ action.actionType }}</strong>
                          <app-status-badge [value]="action.riskLevel" prefix="Risk" />
                          <app-status-badge [value]="action.status" prefix="Action status" />
                        </div>
                        <span class="small text-secondary font-monospace">v{{ action.version }}</span>
                      </div>
                      <code class="small text-info d-block">{{ action.commandPreview }}</code>
                      <div class="small text-secondary">
                        Rollback: {{ action.rollbackStrategy || 'Non-reversible / requires new proposal' }}
                      </div>
                    </div>
                  }
                }
              </div>
            </div>
          </div>

          <div class="col-lg-6">
            <div class="card healops-surface h-100">
              <div class="card-header border-secondary-subtle">
                <h2 class="h6 mb-0 text-white">Live Tool Execution Progress</h2>
              </div>
              <div class="card-body">
                <app-tool-progress [executions]="toolExecutions()" />
              </div>
            </div>
          </div>
        </div>

        <div class="card healops-surface">
          <div class="card-header border-secondary-subtle">
            <h2 class="h6 mb-0 text-white">Before / After / Post-Rollback Health Comparison</h2>
          </div>
          <div class="card-body">
            @if (signalKeys().length === 0) {
              <p class="text-secondary small mb-0">No health snapshots captured yet.</p>
            } @else {
              <div class="table-responsive">
                <table class="table table-dark table-sm table-bordered align-middle mb-0 small" aria-label="Health comparison table">
                  <thead>
                    <tr>
                      <th scope="col">Signal</th>
                      <th scope="col">
                        BEFORE
                        @if (healthComparison()?.before; as snap) {
                          <div class="fw-normal text-secondary">
                            <time [attr.datetime]="snap.capturedAt">{{ snap.capturedAt | date: 'shortTime' }}</time>
                          </div>
                        }
                      </th>
                      <th scope="col">
                        AFTER
                        @if (healthComparison()?.after; as snap) {
                          <div class="fw-normal text-secondary">
                            <time [attr.datetime]="snap.capturedAt">{{ snap.capturedAt | date: 'shortTime' }}</time>
                          </div>
                        }
                      </th>
                      <th scope="col">
                        POST_ROLLBACK
                        @if (healthComparison()?.postRollback; as snap) {
                          <div class="fw-normal text-secondary">
                            <time [attr.datetime]="snap.capturedAt">{{ snap.capturedAt | date: 'shortTime' }}</time>
                          </div>
                        }
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (key of signalKeys(); track key) {
                      <tr>
                        <td class="font-monospace text-info">{{ key }}</td>
                        <td class="font-monospace">{{ formatSignalValue(healthComparison()?.before?.signals?.[key]) }}</td>
                        <td class="font-monospace">{{ formatSignalValue(healthComparison()?.after?.signals?.[key]) }}</td>
                        <td class="font-monospace">{{ formatSignalValue(healthComparison()?.postRollback?.signals?.[key]) }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </div>

        <div class="card healops-surface">
          <div class="card-header border-secondary-subtle d-flex flex-wrap justify-content-between align-items-center gap-2">
            <h2 class="h6 mb-0 text-white">Collected Evidence ({{ filteredEvidence().length }})</h2>
            <div class="d-flex align-items-center gap-2">
              <label for="evidenceTypeFilter" class="visually-hidden">Filter evidence type</label>
              <select
                id="evidenceTypeFilter"
                class="form-select form-select-sm bg-dark text-white border-secondary w-auto"
                [ngModel]="evidenceTypeFilter()"
                (ngModelChange)="onEvidenceTypeChange($event)"
              >
                <option value="">All evidence types</option>
                @for (evType of evidenceTypes; track evType) {
                  <option [value]="evType">{{ evType }}</option>
                }
              </select>
            </div>
          </div>
          <div class="card-body p-0">
            @if (filteredEvidence().length === 0) {
              <p class="text-secondary small p-3 mb-0">No evidence items match the current filter.</p>
            } @else {
              <div class="table-responsive">
                <table class="table table-dark table-hover align-middle mb-0 small" aria-label="Incident evidence items">
                  <thead>
                    <tr>
                      <th scope="col">Type</th>
                      <th scope="col">Source / Target</th>
                      <th scope="col">Observed</th>
                      <th scope="col">Redaction</th>
                      <th scope="col">Excerpt</th>
                      <th scope="col"><span class="visually-hidden">Inspect</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (item of filteredEvidence(); track item.id) {
                      <tr>
                        <td><app-status-badge [value]="item.type" prefix="Evidence type" /></td>
                        <td>
                          <div class="fw-semibold text-white">{{ item.source }}</div>
                          <div class="text-secondary font-monospace">{{ item.target }}</div>
                        </td>
                        <td>
                          <time [attr.datetime]="item.observedAt">{{ item.observedAt | date: 'shortTime' }}</time>
                        </td>
                        <td>
                          <span class="badge" [class.text-bg-warning]="item.redactionStatus === 'REDACTED'" [class.text-bg-secondary]="item.redactionStatus !== 'REDACTED'">
                            {{ item.redactionStatus }}
                          </span>
                        </td>
                        <td class="text-truncate" style="max-width: 280px;">{{ item.excerpt }}</td>
                        <td class="text-end">
                          <button type="button" class="btn btn-sm btn-outline-info" (click)="selectedEvidence.set(item)">
                            Inspect
                          </button>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </div>

        <div class="row g-4">
          <div class="col-lg-7">
            <div class="card healops-surface h-100">
              <div class="card-header border-secondary-subtle d-flex justify-content-between align-items-center">
                <h2 class="h6 mb-0 text-white">Final Incident Report</h2>
                @if (report(); as rep) {
                  <div class="btn-group btn-group-sm" role="group" aria-label="Report format view">
                    <button
                      type="button"
                      class="btn"
                      [class.btn-info]="reportView() === 'markdown'"
                      [class.btn-outline-secondary]="reportView() !== 'markdown'"
                      (click)="reportView.set('markdown')"
                    >
                      Markdown
                    </button>
                    <button
                      type="button"
                      class="btn"
                      [class.btn-info]="reportView() === 'structured'"
                      [class.btn-outline-secondary]="reportView() !== 'structured'"
                      (click)="reportView.set('structured')"
                    >
                      Structured JSON
                    </button>
                  </div>
                }
              </div>
              <div class="card-body">
                @if (report(); as rep) {
                  <div class="d-flex justify-content-between align-items-center small text-secondary mb-2">
                    <span>Outcome: <strong class="text-white">{{ rep.finalOutcome }}</strong></span>
                    <time [attr.datetime]="rep.generatedAt">{{ rep.generatedAt | date: 'medium' }}</time>
                  </div>
                  @if (reportView() === 'markdown') {
                    <pre class="healops-pre mb-0">{{ rep.renderedMarkdown }}</pre>
                  } @else {
                    <pre class="healops-pre mb-0">{{ rep.structuredReport | json }}</pre>
                  }
                } @else {
                  <p class="text-secondary small mb-0">
                    {{ reportStatusNote() }}
                  </p>
                }
              </div>
            </div>
          </div>

          <div class="col-lg-5">
            <div class="card healops-surface h-100">
              <div class="card-header border-secondary-subtle">
                <h2 class="h6 mb-0 text-white">Suggested Runbooks (Read-Only Proposals)</h2>
              </div>
              <div class="card-body d-flex flex-column gap-2">
                @if (suggestedRunbooks().length === 0) {
                  <p class="text-secondary small mb-0">No published runbooks matched this incident.</p>
                } @else {
                  @for (suggestion of suggestedRunbooks(); track suggestion.runbookId) {
                    <article class="p-3 rounded border border-secondary-subtle bg-dark d-flex flex-column gap-1">
                      <div class="d-flex justify-content-between align-items-center gap-2">
                        <strong class="text-white">{{ suggestion.name }} (v{{ suggestion.versionNumber }})</strong>
                        <span class="badge text-bg-info">Score {{ suggestion.matchScore | number: '1.0-1' }}</span>
                      </div>
                      <div class="small text-secondary">
                        Matched: {{ suggestion.matchedReasons.join(', ') }}
                      </div>
                      @if (suggestion.historicalSuccessRate !== null) {
                        <div class="small text-success">
                          Historical success: {{ suggestion.historicalSuccessRate * 100 | number: '1.0-0' }}%
                          ({{ suggestion.historicalOutcomeCount }} runs)
                        </div>
                      }
                      <div class="small text-warning">
                        <i class="bi bi-shield-lock me-1" aria-hidden="true"></i>
                        Read-only proposal; normal policy and operator approval still apply.
                      </div>
                    </article>
                  }
                }
              </div>
            </div>
          </div>
        </div>
        }
      }
    </section>

    <app-evidence-dialog [evidence]="selectedEvidence()" (closed)="selectedEvidence.set(null)" />
  `,
})
export class IncidentDetailComponent {
  readonly incidentId = input.required<string>();

  readonly authStore = inject(AuthStore);
  private readonly api = inject(HealopsApiClient);
  private readonly sse = inject(DashboardEventsService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly evidenceTypes: readonly EvidenceType[] = [
    'ALERT_CONTEXT',
    'METRIC',
    'LOG',
    'KUBERNETES_EVENT',
    'OPENSTACK_EVENT',
    'TRACE',
    'COLLECTION_ERROR',
  ];

  readonly incident = signal<IncidentResponse | null>(null);
  readonly timeline = signal<readonly IncidentEventResponse[]>([]);
  readonly evidence = signal<readonly EvidenceItemResponse[]>([]);
  readonly diagnoses = signal<readonly DiagnosisResponse[]>([]);
  readonly proposedActions = signal<readonly ProposedActionResponse[]>([]);
  readonly healthComparison = signal<HealthComparisonResponse | null>(null);
  readonly toolExecutions = signal<readonly ToolExecutionResponse[]>([]);
  readonly report = signal<IncidentReportResponse | null>(null);
  readonly suggestedRunbooks = signal<readonly RunbookSuggestionResponse[]>([]);

  readonly selectedEvidence = signal<EvidenceItemResponse | null>(null);
  readonly evidenceTypeFilter = signal<EvidenceType | ''>('');
  readonly reportView = signal<'markdown' | 'structured'>('markdown');
  readonly loading = signal<boolean>(false);
  readonly error = signal<string | null>(null);
  readonly actionNotice = signal<string | null>(null);
  readonly retrying = signal<boolean>(false);
  readonly draftingRunbook = signal<boolean>(false);

  readonly filteredEvidence = computed(() => {
    const typeFilter = this.evidenceTypeFilter();
    const list = this.evidence();
    return typeFilter ? list.filter((item) => item.type === typeFilter) : list;
  });

  readonly signalKeys = computed<readonly string[]>(() => {
    const comp = this.healthComparison();
    if (!comp) {
      return [];
    }
    const keys = new Set<string>();
    for (const snap of [comp.before, comp.after, comp.postRollback]) {
      if (snap) {
        for (const k of Object.keys(snap.signals)) {
          keys.add(k);
        }
      }
    }
    return [...keys].sort();
  });

  readonly reportStatusNote = computed(() => {
    const inc = this.incident();
    if (!inc) {
      return 'Final report unavailable.';
    }
    return TERMINAL_INCIDENT_STATUSES.has(inc.status)
      ? 'Generating or loading final incident report...'
      : `Final report is automatically generated once the incident reaches a terminal state (current: ${inc.status}).`;
  });

  constructor() {
    effect(() => {
      const id = this.incidentId();
      if (id) {
        void this.loadAll(id);
      }
    });

    this.sse
      .ofType('incident.updated', 'tool-execution.updated', 'approval.created', 'approval.updated')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((envelope) => {
        const currentId = this.incidentId();
        if (currentId && envelope.incidentId === currentId) {
          void this.loadAll(currentId, true);
        }
      });
  }

  onEvidenceTypeChange(value: string): void {
    this.evidenceTypeFilter.set(value as EvidenceType | '');
  }

  reload(): void {
    const id = this.incidentId();
    if (id) {
      void this.loadAll(id);
    }
  }

  openEvidenceById(evidenceId: string): void {
    const found = this.evidence().find((item) => item.id === evidenceId) ?? null;
    if (found) {
      this.selectedEvidence.set(found);
    } else {
      this.actionNotice.set(`Cited evidence ${evidenceId} is not present in the loaded list.`);
    }
  }

  formatSignalValue(value: unknown): string {
    if (value === undefined || value === null) {
      return '—';
    }
    if (typeof value === 'object') {
      return JSON.stringify(value);
    }
    return String(value);
  }

  async retryFailedPhase(inc: IncidentResponse): Promise<void> {
    if (this.retrying()) {
      return;
    }
    this.retrying.set(true);
    this.actionNotice.set(null);
    try {
      const updated = await firstValueFrom(
        this.api.retryIncident(inc.id, {
          reason: 'Operator requested retry from incident detail view',
          expectedVersion: inc.version,
        })
      );
      this.incident.set(updated);
      this.actionNotice.set(`Retry accepted; incident moved to ${updated.status}.`);
      await this.loadAll(inc.id, true);
    } catch (err: unknown) {
      this.actionNotice.set(toApiErrorMessage(err, 'Failed to retry incident phase.'));
      if (err instanceof ApiError && err.isConflict) {
        await this.loadAll(inc.id, true);
      }
    } finally {
      this.retrying.set(false);
    }
  }

  async draftRunbook(inc: IncidentResponse): Promise<void> {
    if (this.draftingRunbook()) {
      return;
    }
    this.draftingRunbook.set(true);
    this.actionNotice.set(null);
    try {
      const drafted = await firstValueFrom(
        this.api.draftRunbookFromIncident(inc.id, {
          name: `Runbook: ${inc.service} (${inc.severity})`,
        })
      );
      this.actionNotice.set(`Draft runbook "${drafted.name}" created (v${drafted.currentVersion}).`);
      void this.router.navigate(['/runbooks'], { queryParams: { runbookId: drafted.id } });
    } catch (err: unknown) {
      this.actionNotice.set(toApiErrorMessage(err, 'Unable to draft runbook from incident.'));
    } finally {
      this.draftingRunbook.set(false);
    }
  }

  private async loadAll(incidentId: string, silent = false): Promise<void> {
    if (!silent) {
      this.loading.set(true);
    }
    this.error.set(null);
    try {
      const inc = await firstValueFrom(this.api.getIncident(incidentId));
      this.incident.set(inc);

      const [
        timeline,
        evidence,
        diagnoses,
        proposedActions,
        healthComparison,
        toolExecutions,
        suggestions,
      ] = await Promise.all([
        firstValueFrom(this.api.getIncidentTimeline(incidentId)).catch(() => []),
        firstValueFrom(this.api.getIncidentEvidence(incidentId)).catch(() => []),
        firstValueFrom(this.api.getIncidentDiagnoses(incidentId)).catch(() => []),
        firstValueFrom(this.api.getIncidentProposedActions(incidentId)).catch(() => []),
        firstValueFrom(this.api.getIncidentHealthComparisons(incidentId)).catch(() => null),
        firstValueFrom(this.api.getToolExecutions(incidentId)).catch(() => []),
        firstValueFrom(this.api.getRunbookSuggestions(incidentId)).catch(() => []),
      ]);

      this.timeline.set(timeline);
      this.evidence.set(evidence);
      this.diagnoses.set(diagnoses);
      this.proposedActions.set(proposedActions);
      this.healthComparison.set(healthComparison);
      this.toolExecutions.set(toolExecutions);
      this.suggestedRunbooks.set(suggestions);

      if (TERMINAL_INCIDENT_STATUSES.has(inc.status)) {
        const rep = await firstValueFrom(this.api.getIncidentReport(incidentId)).catch(() => null);
        this.report.set(rep);
      } else {
        this.report.set(null);
      }
    } catch (err: unknown) {
      this.error.set(toApiErrorMessage(err, 'Unable to load incident details.'));
    } finally {
      if (!silent) {
        this.loading.set(false);
      }
    }
  }
}

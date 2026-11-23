import { DatePipe, DecimalPipe, JsonPipe, KeyValuePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  SecurityContext,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { DomSanitizer } from '@angular/platform-browser';
import { Router, RouterLink } from '@angular/router';
import { marked } from 'marked';
import { firstValueFrom } from 'rxjs';
import {
  ApprovalDecisionType,
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
import { generateCorrelationId } from '../../core/api/api.interceptor';
import { HealopsApiClient } from '../../core/api/healops-api.client';
import { ApiError, toApiErrorMessage } from '../../core/api/problem-detail';
import { DashboardEventsService } from '../../core/events/dashboard-events.service';
import { AuthStore } from '../../core/state/auth.store';
import { EvidenceDialogComponent } from '../../shared/evidence-dialog/evidence-dialog.component';
import { IncidentTimelineComponent } from '../../shared/incident-timeline/incident-timeline.component';
import { StatusBadgeComponent } from '../../shared/status-badge/status-badge.component';
import { ToolProgressComponent } from '../../shared/tool-progress/tool-progress.component';

interface LifecycleStepView {
  readonly stepNumber: number;
  readonly title: string;
  readonly subtitle: string;
  readonly tone: 'done' | 'active' | 'warning' | 'danger' | 'pending';
}

const SIGNAL_LABELS: Record<string, string> = {
  healthStatus: 'Overall Health Status',
  workloadStatus: 'Workload Runtime State',
  telemetryStatus: 'Telemetry Completeness',
  findingCodes: 'Detected Fault Codes',
  maxLatencyMs: 'Peak Latency (ms)',
  maxErrorRatePercent: 'Peak Error Rate (%)',
  maxRestartCount: 'Container Restarts',
  maxCpuPercent: 'Peak CPU Utilization (%)',
  maxMemoryPercent: 'Peak Memory Utilization (%)',
  runtimeEvidenceCount: 'Runtime Evidence Sources',
  totalEvidence: 'Total Evidence Items',
  errorCount: 'Collection Errors',
  failedSources: 'Unavailable Sources',
};

const SIGNAL_ORDER: readonly string[] = [
  'healthStatus',
  'workloadStatus',
  'telemetryStatus',
  'findingCodes',
  'maxLatencyMs',
  'maxErrorRatePercent',
  'maxRestartCount',
  'maxCpuPercent',
  'maxMemoryPercent',
  'runtimeEvidenceCount',
  'totalEvidence',
  'errorCount',
  'failedSources',
];

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
            <i class="bi bi-arrow-left me-1" aria-hidden="true"></i>Back to Incidents
          </a>
          <h1 class="h4 mb-0 text-white fw-bold">Incident Investigation</h1>
        </div>
        <div class="d-flex flex-wrap gap-2">
          @if (incident(); as inc) {
            @if (inc.status === 'FAILED' && authStore.canWrite()) {
              <button
                type="button"
                class="btn btn-sm btn-warning fw-semibold"
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
        <div class="alert alert-info py-2 small mb-0 d-flex justify-content-between align-items-center" role="status">
          <span>{{ notice }}</span>
          <button type="button" class="btn-close btn-close-white btn-sm" aria-label="Dismiss notice" (click)="actionNotice.set(null)"></button>
        </div>
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
            <div class="d-flex flex-wrap justify-content-between align-items-start gap-3">
              <div>
                <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
                  <app-status-badge [value]="inc.severity" prefix="Severity" />
                  <app-status-badge [value]="inc.status" prefix="Status" />
                  <span class="badge text-bg-info font-monospace">env: {{ inc.environmentId }}</span>
                  <span class="badge text-bg-dark border border-secondary-subtle font-monospace">v{{ inc.version }}</span>
                </div>
                <h2 class="h4 mb-1 text-white fw-bold">{{ inc.service }}</h2>
                <div class="small text-light-emphasis">
                  {{ inc.annotations['summary'] || 'Automated infrastructure anomaly detected' }}
                </div>
              </div>
              <div class="small text-secondary text-md-end healops-subcard p-2 px-3">
                <div>
                  Started: <strong class="text-light"><time [attr.datetime]="inc.startedAt">{{ inc.startedAt | date: 'medium' }}</time></strong>
                </div>
                <div>Alert Source: <strong class="text-info">{{ inc.source }}</strong></div>
                <div class="font-monospace" style="font-size: 0.74rem;">ID: {{ inc.id }}</div>
                @if (inc.traceId) {
                  <div class="font-monospace mt-1">
                    Trace:
                    <a class="link-info text-decoration-none" [routerLink]="['/services']" [queryParams]="{ traceId: inc.traceId }">
                      {{ inc.traceId }}
                    </a>
                  </div>
                }
              </div>
            </div>

            @if ((inc.annotations | keyvalue).length > 0 || (inc.labels | keyvalue).length > 0) {
              <div class="d-flex flex-wrap align-items-center gap-2 pt-2 border-top border-secondary-subtle small">
                <span class="text-secondary text-uppercase fw-semibold" style="font-size: 0.72rem;">Target &amp; Signals:</span>
                @for (entry of inc.labels | keyvalue; track entry.key) {
                  <span class="healops-kv-chip">
                    <strong>{{ entry.key }}</strong> {{ entry.value }}
                  </span>
                }
                @for (entry of inc.annotations | keyvalue; track entry.key) {
                  @if (entry.key !== 'summary') {
                    <span class="healops-kv-chip">
                      <strong>{{ entry.key }}</strong> {{ entry.value }}
                    </span>
                  }
                }
              </div>
            }

            <div class="healops-stepper pt-1" aria-label="Autopilot workflow progress">
              @for (step of lifecycleSteps(); track step.stepNumber) {
                <div
                  class="healops-step"
                  [class.healops-step--done]="step.tone === 'done'"
                  [class.healops-step--active]="step.tone === 'active'"
                  [class.healops-step--warning]="step.tone === 'warning'"
                  [class.healops-step--danger]="step.tone === 'danger'"
                >
                  <div class="d-flex justify-content-between align-items-center mb-1">
                    <span class="fw-bold text-white">{{ step.stepNumber }}. {{ step.title }}</span>
                    @if (step.tone === 'done') {
                      <i class="bi bi-check-circle-fill text-success" aria-hidden="true"></i>
                    } @else if (step.tone === 'active') {
                      <i class="bi bi-activity text-info" aria-hidden="true"></i>
                    } @else if (step.tone === 'warning') {
                      <i class="bi bi-exclamation-triangle-fill text-warning" aria-hidden="true"></i>
                    } @else if (step.tone === 'danger') {
                      <i class="bi bi-x-octagon-fill text-danger" aria-hidden="true"></i>
                    }
                  </div>
                  <div class="text-secondary" style="font-size: 0.73rem;">{{ step.subtitle }}</div>
                </div>
              }
            </div>
          </div>
        </div>

        <div class="row g-4">
          <div class="col-lg-6">
            <div class="card healops-surface h-100">
              <div class="card-header d-flex justify-content-between align-items-center">
                <h2 class="h6 mb-0 text-white">AI &amp; Deterministic Root-Cause Diagnosis</h2>
                @if (diagnoses().length > 0) {
                  <span class="badge text-bg-info">{{ diagnoses()[0].confidence * 100 | number: '1.0-0' }}% Confidence</span>
                }
              </div>
              <div class="card-body d-flex flex-column gap-3">
                @if (diagnoses().length === 0) {
                  <p class="text-secondary small mb-0">No diagnosis persisted yet.</p>
                } @else {
                  @for (diag of diagnoses(); track diag.id) {
                    <article class="p-3 healops-subcard d-flex flex-column gap-3">
                      <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 border-bottom border-secondary-subtle pb-2">
                        <div>
                          <div class="small text-secondary text-uppercase fw-semibold">Executive Diagnosis Summary</div>
                          <div class="text-white fw-medium mt-1">{{ diag.summary }}</div>
                        </div>
                        <span class="badge text-bg-dark border border-secondary-subtle font-monospace">
                          {{ diag.modelVersion }}
                        </span>
                      </div>

                      @if (diag.hypotheses.length > 0) {
                        <div class="d-flex flex-column gap-2">
                          <div class="small text-secondary text-uppercase fw-semibold">
                            Ranked Root-Cause Hypotheses ({{ diag.hypotheses.length }})
                          </div>
                          @for (hyp of diag.hypotheses; track hyp.title; let idx = $index) {
                            <div class="p-2 px-3 rounded bg-dark border border-secondary-subtle d-flex flex-column gap-1">
                              <div class="d-flex flex-wrap justify-content-between align-items-center gap-2">
                                <div class="fw-bold text-info small">
                                  #{{ idx + 1 }} &bull; {{ hyp.title }}
                                </div>
                                <span class="badge text-bg-secondary">{{ hyp.confidence * 100 | number: '1.0-0' }}% match</span>
                              </div>
                              <div class="small text-light-emphasis">{{ hyp.description }}</div>
                              @if (hyp.evidenceIds.length > 0) {
                                <div class="d-flex flex-wrap align-items-center gap-1 mt-1 pt-1 border-top border-secondary-subtle">
                                  <span class="text-secondary" style="font-size: 0.72rem;">Cited Evidence:</span>
                                  @for (evId of hyp.evidenceIds; track evId) {
                                    <button
                                      type="button"
                                      class="btn btn-outline-info btn-sm py-0 px-2 font-monospace"
                                      style="font-size: 0.74rem;"
                                      (click)="openEvidenceById(evId)"
                                    >
                                      {{ citeLabel(evId) }}
                                    </button>
                                  }
                                </div>
                              }
                            </div>
                          }
                        </div>
                      }
                    </article>
                  }
                }
              </div>
            </div>
          </div>

          <div class="col-lg-6">
            <div class="card healops-surface h-100">
              <div class="card-header d-flex justify-content-between align-items-center">
                <h2 class="h6 mb-0 text-white">Proposed Remediation &amp; Approval</h2>
                <a class="small link-warning text-decoration-none" routerLink="/approvals">Approval Queue &rarr;</a>
              </div>
              <div class="card-body d-flex flex-column gap-3">
                @if (proposedActions().length === 0) {
                  <p class="text-secondary small mb-0">No remediation actions proposed yet.</p>
                } @else {
                  @for (action of proposedActions(); track action.id) {
                    <div class="p-3 healops-subcard d-flex flex-column gap-3">
                      <div class="d-flex flex-wrap justify-content-between align-items-start gap-2">
                        <div>
                          <div class="h6 mb-1 text-white fw-bold">{{ formatActionTitle(action.actionType) }}</div>
                          <div class="d-flex flex-wrap align-items-center gap-2">
                            <span class="font-monospace small text-info">{{ action.actionType }}</span>
                            <app-status-badge [value]="action.riskLevel" prefix="Risk" />
                            <app-status-badge [value]="action.status" prefix="Action status" />
                          </div>
                        </div>
                        <span class="badge text-bg-dark border border-secondary-subtle font-monospace">v{{ action.version }}</span>
                      </div>

                      @if ((action.arguments | keyvalue).length > 0) {
                        <div>
                          <div class="small text-secondary text-uppercase fw-semibold mb-1" style="font-size: 0.72rem;">
                            Target Parameters
                          </div>
                          <div class="d-flex flex-wrap gap-1">
                            @for (arg of action.arguments | keyvalue; track arg.key) {
                              <span class="healops-kv-chip">
                                <strong>{{ arg.key }}</strong> {{ formatSignalValue(arg.value) }}
                              </span>
                            }
                          </div>
                        </div>
                      }

                      <div>
                        <div class="small text-secondary text-uppercase fw-semibold mb-1" style="font-size: 0.72rem;">
                          Equivalent CLI Command Preview
                        </div>
                        <pre class="healops-pre py-2 mb-0 text-info">{{ action.commandPreview }}</pre>
                      </div>

                      <div class="small text-secondary">
                        <i class="bi bi-arrow-counterclockwise me-1 text-info" aria-hidden="true"></i>
                        Rollback safety: <strong class="text-light">{{ action.rollbackStrategy || 'Non-reversible / requires new proposal' }}</strong>
                      </div>

                      @if (action.status === 'PROPOSED') {
                        <div class="pt-2 border-top border-secondary-subtle d-flex flex-column gap-2">
                          @if (authStore.canApprove()) {
                            <div class="input-group input-group-sm">
                              <input
                                type="text"
                                class="form-control bg-dark text-white border-secondary"
                                placeholder="Optional approval/rejection reason for audit log..."
                                [ngModel]="inlineDecisionReason()"
                                (ngModelChange)="inlineDecisionReason.set($event)"
                                aria-label="Approval decision reason"
                              />
                              <button
                                type="button"
                                class="btn btn-success fw-semibold"
                                [disabled]="decidingActionId() === action.id"
                                (click)="submitInlineDecision(action, 'APPROVED')"
                              >
                                <i class="bi bi-check-lg me-1" aria-hidden="true"></i>Approve &amp; Execute
                              </button>
                              <button
                                type="button"
                                class="btn btn-outline-danger"
                                [disabled]="decidingActionId() === action.id"
                                (click)="submitInlineDecision(action, 'REJECTED')"
                              >
                                <i class="bi bi-x-lg me-1" aria-hidden="true"></i>Reject
                              </button>
                            </div>
                          } @else {
                            <div class="small text-warning">
                              <i class="bi bi-lock-fill me-1" aria-hidden="true"></i>
                              Requires <code>incident:approve</code> authority to approve or reject.
                            </div>
                          }
                        </div>
                      }
                    </div>
                  }
                }

                <div class="pt-2 border-top border-secondary-subtle">
                  <div class="small text-secondary text-uppercase fw-semibold mb-2">Tool Execution Audit</div>
                  <app-tool-progress [executions]="toolExecutions()" />
                </div>
              </div>
            </div>
          </div>
        </div>

        <div class="card healops-surface">
          <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
            <h2 class="h6 mb-0 text-white">Before vs. After Remediation Health Verification</h2>
            @if (healthComparison()?.before || healthComparison()?.after) {
              <div class="d-flex align-items-center gap-2 small">
                @if (healthComparison()?.before; as bSnap) {
                  <span class="text-secondary">BEFORE:</span>
                  <app-status-badge [value]="asString(bSnap.signals['healthStatus'], 'UNKNOWN')" prefix="Before health" />
                }
                @if (healthComparison()?.after; as aSnap) {
                  <span class="text-secondary">&rarr; AFTER:</span>
                  <app-status-badge [value]="asString(aSnap.signals['healthStatus'], 'UNKNOWN')" prefix="After health" />
                }
                @if (healthComparison()?.postRollback; as rSnap) {
                  <span class="text-secondary">&rarr; POST-ROLLBACK:</span>
                  <app-status-badge [value]="asString(rSnap.signals['healthStatus'], 'UNKNOWN')" prefix="Post-rollback health" />
                }
              </div>
            }
          </div>
          <div class="card-body">
            @if (signalKeys().length === 0) {
              <p class="text-secondary small mb-0">No health verification snapshots captured yet.</p>
            } @else {
              <div class="table-responsive">
                <table class="table table-dark table-sm table-hover align-middle mb-0 small" aria-label="Health comparison table">
                  <thead>
                    <tr>
                      <th scope="col">Health Signal</th>
                      <th scope="col">
                        BEFORE Remediation
                        @if (healthComparison()?.before; as snap) {
                          <span class="fw-normal text-secondary ms-1">
                            (<time [attr.datetime]="snap.capturedAt">{{ snap.capturedAt | date: 'mediumTime' }}</time>)
                          </span>
                        }
                      </th>
                      <th scope="col">
                        AFTER Remediation
                        @if (healthComparison()?.after; as snap) {
                          <span class="fw-normal text-secondary ms-1">
                            (<time [attr.datetime]="snap.capturedAt">{{ snap.capturedAt | date: 'mediumTime' }}</time>)
                          </span>
                        }
                      </th>
                      @if (healthComparison()?.postRollback; as snap) {
                        <th scope="col">
                          POST_ROLLBACK
                          <span class="fw-normal text-secondary ms-1">
                            (<time [attr.datetime]="snap.capturedAt">{{ snap.capturedAt | date: 'mediumTime' }}</time>)
                          </span>
                        </th>
                      }
                    </tr>
                  </thead>
                  <tbody>
                    @for (key of signalKeys(); track key) {
                      <tr>
                        <td>
                          <div class="fw-semibold text-white">{{ formatSignalLabel(key) }}</div>
                          <div class="font-monospace text-secondary" style="font-size: 0.72rem;">{{ key }}</div>
                        </td>
                        <td>
                          @if (isBadgeSignal(key) && healthComparison()?.before?.signals?.[key]) {
                            <app-status-badge [value]="formatSignalValue(healthComparison()?.before?.signals?.[key])" />
                          } @else {
                            <span class="font-monospace">{{ formatSignalValue(healthComparison()?.before?.signals?.[key]) }}</span>
                          }
                        </td>
                        <td>
                          @if (isBadgeSignal(key) && healthComparison()?.after?.signals?.[key]) {
                            <app-status-badge [value]="formatSignalValue(healthComparison()?.after?.signals?.[key])" />
                          } @else {
                            <span class="font-monospace text-info fw-semibold">{{ formatSignalValue(healthComparison()?.after?.signals?.[key]) }}</span>
                          }
                        </td>
                        @if (healthComparison()?.postRollback) {
                          <td>
                            @if (isBadgeSignal(key) && healthComparison()?.postRollback?.signals?.[key]) {
                              <app-status-badge [value]="formatSignalValue(healthComparison()?.postRollback?.signals?.[key])" />
                            } @else {
                              <span class="font-monospace">{{ formatSignalValue(healthComparison()?.postRollback?.signals?.[key]) }}</span>
                            }
                          </td>
                        }
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </div>

        <div class="card healops-surface">
          <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
            <div>
              <h2 class="h6 mb-0 text-white">Collected Telemetry &amp; Infrastructure Evidence ({{ filteredEvidence().length }})</h2>
              <div class="small text-secondary">Click any evidence row or "Inspect Payload" to open the full structured telemetry drawer</div>
            </div>
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
                      <th scope="col">Evidence Type</th>
                      <th scope="col">Adapter / Target</th>
                      <th scope="col">Parsed Signal Summary</th>
                      <th scope="col">Observed</th>
                      <th scope="col" class="text-end">Details</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (item of filteredEvidence(); track item.id) {
                      <tr role="button" (click)="selectedEvidence.set(item)">
                        <td>
                          <app-status-badge [value]="item.type" prefix="Evidence type" />
                          <div class="font-monospace text-secondary mt-1" style="font-size: 0.72rem;">
                            #{{ item.id.slice(0, 8) }}
                          </div>
                        </td>
                        <td>
                          <div class="fw-bold text-white">{{ item.source }}</div>
                          <div class="text-info font-monospace">{{ item.target }}</div>
                        </td>
                        <td style="max-width: 520px;">
                          <div class="text-light">{{ summarizeEvidenceExcerpt(item) }}</div>
                          @if (evidenceChips(item).length > 0) {
                            <div class="d-flex flex-wrap gap-1 mt-1">
                              @for (chip of evidenceChips(item); track chip.key) {
                                <span class="healops-kv-chip">
                                  <strong>{{ chip.key }}</strong> {{ chip.value }}
                                </span>
                              }
                            </div>
                          }
                        </td>
                        <td class="text-nowrap">
                          <time [attr.datetime]="item.observedAt">{{ item.observedAt | date: 'mediumTime' }}</time>
                          <div>
                            <span class="badge" [class.text-bg-warning]="item.redactionStatus === 'REDACTED'" [class.text-bg-secondary]="item.redactionStatus !== 'REDACTED'">
                              {{ item.redactionStatus }}
                            </span>
                          </div>
                        </td>
                        <td class="text-end text-nowrap" (click)="$event.stopPropagation()">
                          <button type="button" class="btn btn-sm btn-outline-info" (click)="selectedEvidence.set(item)">
                            Inspect Payload
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
          <div class="col-lg-6">
            <div class="card healops-surface h-100">
              <div class="card-header">
                <h2 class="h6 mb-0 text-white">Ordered Workflow Audit Timeline ({{ timeline().length }})</h2>
              </div>
              <div class="card-body">
                <app-incident-timeline [events]="timeline()" />
              </div>
            </div>
          </div>

          <div class="col-lg-6 d-flex flex-column gap-4">
            <div class="card healops-surface">
              <div class="card-header d-flex justify-content-between align-items-center">
                <h2 class="h6 mb-0 text-white">Final Post-Incident Report</h2>
                @if (report(); as rep) {
                  <div class="btn-group btn-group-sm" role="group" aria-label="Report format view">
                    <button
                      type="button"
                      class="btn"
                      [class.btn-info]="reportView() === 'markdown'"
                      [class.btn-outline-secondary]="reportView() !== 'markdown'"
                      (click)="reportView.set('markdown')"
                    >
                      Formatted Report
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
                    <div class="healops-markdown small text-light" [innerHTML]="renderedReportHtml()"></div>
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

            <div class="card healops-surface">
              <div class="card-header">
                <h2 class="h6 mb-0 text-white">Suggested Runbooks (Read-Only Proposals)</h2>
              </div>
              <div class="card-body d-flex flex-column gap-2">
                @if (suggestedRunbooks().length === 0) {
                  <p class="text-secondary small mb-0">No published runbooks matched this incident yet.</p>
                } @else {
                  @for (suggestion of suggestedRunbooks(); track suggestion.runbookId) {
                    <article class="p-3 healops-subcard d-flex flex-column gap-1">
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
  private readonly sanitizer = inject(DomSanitizer);
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
  readonly inlineDecisionReason = signal<string>('');
  readonly decidingActionId = signal<string | null>(null);

  readonly filteredEvidence = computed(() => {
    const typeFilter = this.evidenceTypeFilter();
    const list = this.evidence();
    return typeFilter ? list.filter((item) => item.type === typeFilter) : list;
  });

  readonly lifecycleSteps = computed<readonly LifecycleStepView[]>(() => {
    const inc = this.incident();
    if (!inc) {
      return [];
    }
    const st = inc.status;
    const evCount = this.evidence().length;
    const diag = this.diagnoses()[0];
    const action = this.proposedActions()[0];
    const isTerminal = TERMINAL_INCIDENT_STATUSES.has(st);

    const stageRank: Record<string, number> = {
      ALERTED: 1,
      GATHERING_EVIDENCE: 2,
      CORRELATING_SIGNALS: 2,
      DIAGNOSING: 3,
      AWAITING_APPROVAL: 4,
      EXECUTING: 5,
      VERIFYING: 5,
      ROLLING_BACK: 5,
      CLOSED: 6,
      ROLLED_BACK: 6,
      FAILED: 6,
      CANCELLED: 6,
    };
    const rank = stageRank[st] ?? 1;

    const toneFor = (stepIdx: number): LifecycleStepView['tone'] => {
      if (rank > stepIdx) {
        return 'done';
      }
      if (rank === stepIdx) {
        if (st === 'CLOSED') {
          return 'done';
        }
        if (st === 'AWAITING_APPROVAL' || st === 'ROLLED_BACK' || st === 'ROLLING_BACK') {
          return 'warning';
        }
        if (st === 'FAILED' || st === 'CANCELLED') {
          return 'danger';
        }
        return 'active';
      }
      return 'pending';
    };

    return [
      {
        stepNumber: 1,
        title: 'Alert Ingested',
        subtitle: `${inc.source} (${inc.severity})`,
        tone: 'done',
      },
      {
        stepNumber: 2,
        title: 'Evidence Collected',
        subtitle: evCount > 0 ? `${evCount} telemetry items` : 'Collecting signals...',
        tone: toneFor(2),
      },
      {
        stepNumber: 3,
        title: 'AI Diagnosis',
        subtitle: diag ? `${Math.round(diag.confidence * 100)}% confidence (${diag.hypotheses.length} hypotheses)` : 'Correlating root cause...',
        tone: toneFor(3),
      },
      {
        stepNumber: 4,
        title: 'Operator Approval',
        subtitle: action ? `${action.actionType} (${action.status})` : 'Policy gate',
        tone: toneFor(4),
      },
      {
        stepNumber: 5,
        title: 'Execute & Verify',
        subtitle: this.healthComparison()?.after
          ? `AFTER: ${this.asString(this.healthComparison()?.after?.signals?.['healthStatus'], 'VERIFIED')}`
          : 'Adapter execution & probe check',
        tone: toneFor(5),
      },
      {
        stepNumber: 6,
        title: 'Final Outcome',
        subtitle: isTerminal ? st : 'Awaiting completion',
        tone: toneFor(6),
      },
    ];
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
    return [...keys].sort((a, b) => {
      const idxA = SIGNAL_ORDER.indexOf(a);
      const idxB = SIGNAL_ORDER.indexOf(b);
      if (idxA !== -1 && idxB !== -1) {
        return idxA - idxB;
      }
      if (idxA !== -1) {
        return -1;
      }
      if (idxB !== -1) {
        return 1;
      }
      return a.localeCompare(b);
    });
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

  readonly renderedReportHtml = computed(() => {
    const markdown = this.report()?.renderedMarkdown ?? '';
    if (!markdown.trim()) {
      return '';
    }
    const parsedHtml = marked.parse(markdown, { async: false });
    return this.sanitizer.sanitize(SecurityContext.HTML, parsedHtml) ?? '';
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

  citeLabel(evidenceId: string): string {
    const found = this.evidence().find((item) => item.id === evidenceId);
    if (!found) {
      return `Cite ${evidenceId.slice(0, 8)}`;
    }
    return `${found.type} • ${found.source} (${evidenceId.slice(0, 8)})`;
  }

  openEvidenceById(evidenceId: string): void {
    const found = this.evidence().find((item) => item.id === evidenceId) ?? null;
    if (found) {
      this.selectedEvidence.set(found);
    } else {
      this.actionNotice.set(`Cited evidence ${evidenceId} is not present in the loaded list.`);
    }
  }

  formatActionTitle(actionType: string): string {
    switch (actionType) {
      case 'ROLLBACK_K8S_DEPLOYMENT':
        return 'Rollback Kubernetes Deployment';
      case 'SCALE_K8S_DEPLOYMENT':
        return 'Scale Kubernetes Deployment Replicas';
      case 'RESTART_K8S_WORKLOAD':
        return 'Restart Kubernetes Workload';
      case 'RESTART_CONTAINER':
        return 'Restart Docker Container';
      case 'RESTART_OPENSTACK_SERVICE':
        return 'Restart OpenStack Service';
      case 'NO_ACTION':
        return 'Manual Investigation Required (No Automated Action)';
      default:
        return actionType;
    }
  }

  formatSignalLabel(key: string): string {
    return SIGNAL_LABELS[key] ?? key;
  }

  isBadgeSignal(key: string): boolean {
    return key === 'healthStatus' || key === 'telemetryStatus';
  }

  asString(value: unknown, fallback: string): string {
    return typeof value === 'string' && value.length > 0 ? value : fallback;
  }

  formatSignalValue(value: unknown): string {
    if (value === undefined || value === null) {
      return '—';
    }
    if (Array.isArray(value)) {
      return value.length === 0 ? 'None' : value.map(String).join(', ');
    }
    if (typeof value === 'object') {
      return JSON.stringify(value);
    }
    return String(value);
  }

  summarizeEvidenceExcerpt(item: EvidenceItemResponse): string {
    const raw = (item.excerpt ?? '').trim();
    if (!raw) {
      return 'Empty excerpt';
    }
    if (raw.startsWith('{')) {
      try {
        const obj = JSON.parse(raw) as Record<string, unknown>;
        if (item.type === 'KUBERNETES_EVENT') {
          const health = typeof obj['health'] === 'string' ? obj['health'].toUpperCase() : 'UNKNOWN';
          const pods = Array.isArray(obj['pods']) ? obj['pods'] : [];
          const podDescs = pods
            .filter((p): p is Record<string, unknown> => Boolean(p && typeof p === 'object'))
            .map((p) => {
              const name = typeof p['name'] === 'string' ? p['name'] : 'pod';
              const phase = typeof p['phase'] === 'string' ? p['phase'] : '';
              const ready = typeof p['ready'] === 'string' ? p['ready'] : '';
              const reasons = Array.isArray(p['reasons']) ? p['reasons'].map(String) : [];
              const reason = typeof p['reason'] === 'string' ? p['reason'] : reasons.join(',');
              return reason
                ? `${name} (${phase} ${ready} • ${reason})`
                : `${name} (${phase} ${ready})`;
            });
          return `Kubernetes workload health=${health}${podDescs.length > 0 ? ' — ' + podDescs.join('; ') : ''}`;
        }
        if (item.type === 'ALERT_CONTEXT') {
          const summary = typeof obj['summary'] === 'string' ? obj['summary'] : '';
          if (summary) {
            return summary;
          }
        }
        if (item.type === 'TRACE') {
          const dur = obj['totalDurationMs'] ?? obj['latency_ms'];
          const errs = obj['errorSpanCount'] ?? 0;
          const spans = obj['spanCount'] ?? '';
          return `Distributed trace: ${spans} spans, ${errs} error span(s), total duration ${dur} ms`;
        }
      } catch {
        // Fallback to plain excerpt
      }
    }
    const firstLine = raw.split(/\r?\n/).find((l) => l.trim().length > 0) ?? raw;
    return firstLine.length > 140 ? `${firstLine.slice(0, 140)}…` : firstLine;
  }

  evidenceChips(item: EvidenceItemResponse): readonly { readonly key: string; readonly value: string }[] {
    const raw = (item.excerpt ?? '').trim();
    const chips: { key: string; value: string }[] = [];
    if (raw.startsWith('{')) {
      try {
        const obj = JSON.parse(raw) as Record<string, unknown>;
        const dep = obj['deployment'];
        if (dep && typeof dep === 'object' && !Array.isArray(dep)) {
          const depObj = dep as Record<string, unknown>;
          if (depObj['replicas'] !== undefined) {
            chips.push({ key: 'replicas', value: `${depObj['readyReplicas'] ?? 0}/${depObj['replicas']} ready` });
          }
          if (depObj['revision'] !== undefined) {
            chips.push({ key: 'rev', value: `#${depObj['revision']}` });
          }
          if (depObj['previous_revision'] !== undefined) {
            chips.push({ key: 'prev_rev', value: `#${depObj['previous_revision']}` });
          }
        }
        for (const k of ['latency_ms', 'error_rate', 'status', 'health', 'restarts']) {
          if (obj[k] !== undefined && obj[k] !== null && typeof obj[k] !== 'object') {
            chips.push({ key: k, value: String(obj[k]) });
          }
        }
      } catch {
        // Ignore parse failures
      }
    }
    return chips.slice(0, 5);
  }

  async submitInlineDecision(action: ProposedActionResponse, decision: ApprovalDecisionType): Promise<void> {
    if (!this.authStore.canApprove() || this.decidingActionId()) {
      return;
    }
    this.decidingActionId.set(action.id);
    this.actionNotice.set(null);
    const idempotencyKey = `inline-approval-${action.id}-${generateCorrelationId()}`;
    const defaultReason =
      decision === 'APPROVED'
        ? `Approved ${action.actionType} from incident investigation view`
        : `Rejected ${action.actionType} from incident investigation view`;
    try {
      await firstValueFrom(
        this.api.submitApprovalDecision(action.id, {
          decision,
          reason: this.inlineDecisionReason().trim() || defaultReason,
          expectedVersion: action.version,
          idempotencyKey,
        })
      );
      this.inlineDecisionReason.set('');
      this.actionNotice.set(
        `Action ${action.actionType} ${decision.toLowerCase()}. Executing live workflow and health verification...`
      );
      const id = this.incidentId();
      if (id) {
        await this.loadAll(id, true);
      }
    } catch (err: unknown) {
      this.actionNotice.set(toApiErrorMessage(err, 'Failed to submit approval decision.'));
      const id = this.incidentId();
      if (id) {
        await this.loadAll(id, true);
      }
    } finally {
      this.decidingActionId.set(null);
    }
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

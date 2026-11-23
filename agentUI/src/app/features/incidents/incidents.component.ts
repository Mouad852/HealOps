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
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import {
  IncidentResponse,
  IncidentSeverity,
  IncidentStatus,
} from '../../core/api/api.models';
import { HealopsApiClient } from '../../core/api/healops-api.client';
import { toApiErrorMessage } from '../../core/api/problem-detail';
import { DashboardEventsService } from '../../core/events/dashboard-events.service';
import { EnvironmentStore } from '../../core/state/environment.store';
import { StatusBadgeComponent } from '../../shared/status-badge/status-badge.component';

@Component({
  selector: 'app-incidents',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="d-flex flex-column gap-3" aria-label="Incidents management">
      <div class="d-flex flex-wrap justify-content-between align-items-center gap-2">
        <div>
          <h1 class="h3 mb-1 text-white">Incidents</h1>
          <p class="text-secondary small mb-0">
            Cursor-paginated incident history and active autopilot workflows in
            <strong class="text-info">{{ envStore.selectedEnvironmentId() }}</strong>.
          </p>
        </div>

        <div class="d-flex flex-wrap align-items-center gap-2">
          <div class="btn-group btn-group-sm" role="group" aria-label="Quick status filters">
            <button
              type="button"
              class="btn"
              [class.btn-info]="statusFilter() === ''"
              [class.btn-outline-secondary]="statusFilter() !== ''"
              (click)="onStatusFilterChange('')"
            >
              All
            </button>
            <button
              type="button"
              class="btn"
              [class.btn-warning]="statusFilter() === 'AWAITING_APPROVAL'"
              [class.btn-outline-secondary]="statusFilter() !== 'AWAITING_APPROVAL'"
              (click)="onStatusFilterChange('AWAITING_APPROVAL')"
            >
              Awaiting Approval
            </button>
            <button
              type="button"
              class="btn"
              [class.btn-success]="statusFilter() === 'CLOSED'"
              [class.btn-outline-secondary]="statusFilter() !== 'CLOSED'"
              (click)="onStatusFilterChange('CLOSED')"
            >
              Closed
            </button>
            <button
              type="button"
              class="btn"
              [class.btn-danger]="statusFilter() === 'FAILED'"
              [class.btn-outline-secondary]="statusFilter() !== 'FAILED'"
              (click)="onStatusFilterChange('FAILED')"
            >
              Failed
            </button>
          </div>

          <label class="visually-hidden" for="statusFilter">Filter by status</label>
          <select
            id="statusFilter"
            class="form-select form-select-sm bg-dark text-white border-secondary w-auto"
            [ngModel]="statusFilter()"
            (ngModelChange)="onStatusFilterChange($event)"
          >
            <option value="">All statuses</option>
            @for (status of allStatuses; track status) {
              <option [value]="status">{{ status }}</option>
            }
          </select>

          <label class="visually-hidden" for="severityFilter">Filter by severity</label>
          <select
            id="severityFilter"
            class="form-select form-select-sm bg-dark text-white border-secondary w-auto"
            [ngModel]="severityFilter()"
            (ngModelChange)="onSeverityFilterChange($event)"
          >
            <option value="">All severities</option>
            @for (sev of allSeverities; track sev) {
              <option [value]="sev">{{ sev }}</option>
            }
          </select>

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

      @if (error(); as err) {
        <div class="alert alert-danger d-flex justify-content-between align-items-center mb-0" role="alert">
          <span>{{ err }}</span>
          <button type="button" class="btn btn-sm btn-outline-light" (click)="reload()">Retry</button>
        </div>
      }

      <div class="card healops-surface">
        <div class="card-body p-0">
          @if (loading() && incidents().length === 0) {
            <div class="text-center py-5" role="status">
              <div class="spinner-border text-info" aria-hidden="true"></div>
              <p class="text-secondary small mt-2 mb-0">Loading incidents&hellip;</p>
            </div>
          } @else if (incidents().length === 0) {
            <div class="text-center py-5 text-secondary">
              <i class="bi bi-shield-check fs-2 d-block mb-2 text-success" aria-hidden="true"></i>
              No incidents match the current filters in <strong>{{ envStore.selectedEnvironmentId() }}</strong>.
            </div>
          } @else {
            <div class="table-responsive">
              <table class="table table-dark table-hover align-middle mb-0 small" aria-label="Incidents table">
                <thead>
                  <tr>
                    <th scope="col">Incident</th>
                    <th scope="col">Severity</th>
                    <th scope="col">Service &amp; Infrastructure Target</th>
                    <th scope="col">Alert Summary &amp; Signals</th>
                    <th scope="col">Workflow Status</th>
                    <th scope="col">Started</th>
                    <th scope="col" class="text-end">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  @for (inc of incidents(); track inc.id) {
                    <tr>
                      <td class="font-monospace">
                        <a
                          class="link-info text-decoration-none fw-bold"
                          [routerLink]="['/incidents', inc.id]"
                          [title]="inc.id"
                        >
                          #{{ inc.id.slice(0, 8) }}
                        </a>
                        <div class="text-secondary" style="font-size: 0.72rem;">{{ inc.source }} &bull; v{{ inc.version }}</div>
                      </td>
                      <td><app-status-badge [value]="inc.severity" prefix="Severity" /></td>
                      <td>
                        <div class="fw-bold text-white">{{ inc.service }}</div>
                        <div class="d-flex flex-wrap gap-1 mt-1">
                          @for (chip of targetChips(inc); track chip.key) {
                            <span class="healops-kv-chip">
                              <strong>{{ chip.key }}</strong> {{ chip.value }}
                            </span>
                          }
                        </div>
                      </td>
                      <td style="max-width: 340px;">
                        <div class="text-light">{{ summaryText(inc) }}</div>
                        @if (signalChips(inc).length > 0) {
                          <div class="d-flex flex-wrap gap-1 mt-1">
                            @for (sig of signalChips(inc); track sig.key) {
                              <span class="badge text-bg-dark border border-warning-subtle text-warning font-monospace">
                                {{ sig.key }}: {{ sig.value }}
                              </span>
                            }
                          </div>
                        }
                      </td>
                      <td><app-status-badge [value]="inc.status" prefix="Status" /></td>
                      <td class="text-nowrap">
                        <time [attr.datetime]="inc.startedAt">{{ inc.startedAt | date: 'short' }}</time>
                      </td>
                      <td class="text-end text-nowrap">
                        <div class="d-inline-flex gap-1">
                          <a class="btn btn-sm btn-info fw-semibold" [routerLink]="['/incidents', inc.id]">
                            Investigate
                          </a>
                          <a
                            class="btn btn-sm btn-outline-secondary"
                            [routerLink]="['/chat']"
                            [queryParams]="{ incidentId: inc.id }"
                            title="Ask Copilot about this incident"
                          >
                            <i class="bi bi-chat-dots" aria-hidden="true"></i>
                          </a>
                        </div>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </div>

        @if (nextCursor()) {
          <div class="card-footer border-secondary-subtle text-center">
            <button
              type="button"
              class="btn btn-sm btn-outline-light"
              [disabled]="loadingMore()"
              (click)="loadMore()"
            >
              @if (loadingMore()) {
                <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
              }
              Load more incidents
            </button>
          </div>
        }
      </div>
    </section>
  `,
})
export class IncidentsComponent {
  readonly envStore = inject(EnvironmentStore);
  private readonly api = inject(HealopsApiClient);
  private readonly sse = inject(DashboardEventsService);
  private readonly destroyRef = inject(DestroyRef);

  readonly allStatuses: readonly IncidentStatus[] = [
    'ALERTED',
    'GATHERING_EVIDENCE',
    'CORRELATING_SIGNALS',
    'DIAGNOSING',
    'AWAITING_APPROVAL',
    'EXECUTING',
    'VERIFYING',
    'CLOSED',
    'ROLLING_BACK',
    'ROLLED_BACK',
    'FAILED',
    'CANCELLED',
  ];

  readonly allSeverities: readonly IncidentSeverity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

  readonly incidents = signal<readonly IncidentResponse[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly statusFilter = signal<IncidentStatus | ''>('');
  readonly severityFilter = signal<IncidentSeverity | ''>('');
  readonly loading = signal<boolean>(false);
  readonly loadingMore = signal<boolean>(false);
  readonly error = signal<string | null>(null);

  private readonly pageSize = 20;
  private hasLoadedExtraPages = false;

  constructor() {
    effect(() => {
      const envId = this.envStore.selectedEnvironmentId();
      const status = this.statusFilter();
      const severity = this.severityFilter();
      this.hasLoadedExtraPages = false;
      void this.fetchPage(envId, status, severity, null, false);
    });

    this.sse
      .ofType('incident.updated')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((envelope) => {
        const selectedEnv = this.envStore.selectedEnvironmentId();
        if (envelope.environmentId === selectedEnv) {
          void this.refreshOnSseUpdate(selectedEnv, envelope.incidentId);
        }
      });
  }

  onStatusFilterChange(value: string): void {
    this.statusFilter.set(value as IncidentStatus | '');
  }

  onSeverityFilterChange(value: string): void {
    this.severityFilter.set(value as IncidentSeverity | '');
  }

  summaryText(inc: IncidentResponse): string {
    const s = inc.annotations?.['summary'];
    if (s && s.trim().length > 0) {
      return s.trim();
    }
    return `Automated ${inc.severity.toLowerCase()} incident on ${inc.service}`;
  }

  targetChips(inc: IncidentResponse): readonly { readonly key: string; readonly value: string }[] {
    const chips: { key: string; value: string }[] = [];
    const labels = inc.labels ?? {};
    if (labels['namespace']) {
      chips.push({ key: 'ns', value: labels['namespace'] });
    }
    if (labels['deployment']) {
      chips.push({ key: 'deploy', value: labels['deployment'] });
    } else if (labels['workload']) {
      chips.push({ key: 'workload', value: labels['workload'] });
    }
    if (labels['container']) {
      chips.push({ key: 'container', value: labels['container'] });
    }
    if (labels['node']) {
      chips.push({ key: 'node', value: labels['node'] });
    }
    if (chips.length === 0) {
      chips.push({ key: 'env', value: inc.environmentId });
    }
    return chips;
  }

  signalChips(inc: IncidentResponse): readonly { readonly key: string; readonly value: string }[] {
    const chips: { key: string; value: string }[] = [];
    const ann = inc.annotations ?? {};
    for (const [k, v] of Object.entries(ann)) {
      if (k !== 'summary' && v) {
        chips.push({ key: k, value: v });
      }
    }
    return chips;
  }

  reload(): void {
    this.hasLoadedExtraPages = false;
    void this.fetchPage(
      this.envStore.selectedEnvironmentId(),
      this.statusFilter(),
      this.severityFilter(),
      null,
      false
    );
  }

  loadMore(): void {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore()) {
      return;
    }
    void this.fetchPage(
      this.envStore.selectedEnvironmentId(),
      this.statusFilter(),
      this.severityFilter(),
      cursor,
      true
    );
  }

  private async refreshOnSseUpdate(environmentId: string, updatedIncidentId: string | null): Promise<void> {
    if (!this.hasLoadedExtraPages) {
      await this.fetchPage(environmentId, this.statusFilter(), this.severityFilter(), null, false);
      return;
    }
    try {
      const status = this.statusFilter();
      const severity = this.severityFilter();
      const page1 = await firstValueFrom(
        this.api.getIncidentsPage({
          environmentId,
          status,
          severity,
          cursor: null,
          limit: this.pageSize,
        })
      );
      const page1ById = new Map(page1.items.map((item) => [item.id, item]));
      const existing = this.incidents();
      const existingIds = new Set(existing.map((item) => item.id));
      const newItems = page1.items.filter((item) => !existingIds.has(item.id));

      let extraUpdated: IncidentResponse | null = null;
      if (updatedIncidentId && existingIds.has(updatedIncidentId) && !page1ById.has(updatedIncidentId)) {
        extraUpdated = await firstValueFrom(this.api.getIncident(updatedIncidentId)).catch(() => null);
      }

      const mergedExisting = existing
        .map((item) => {
          if (page1ById.has(item.id)) {
            return page1ById.get(item.id)!;
          }
          if (extraUpdated && item.id === extraUpdated.id) {
            return extraUpdated;
          }
          return item;
        })
        .filter(
          (item) =>
            (!status || item.status === status) && (!severity || item.severity === severity)
        );

      this.incidents.set([...newItems, ...mergedExisting]);
    } catch {
      // Keep already-loaded pages intact if background SSE refresh fails transiently
    }
  }

  private async fetchPage(
    environmentId: string,
    status: IncidentStatus | '',
    severity: IncidentSeverity | '',
    cursor: string | null,
    append: boolean
  ): Promise<void> {
    if (!environmentId) {
      return;
    }
    if (append) {
      this.loadingMore.set(true);
    } else {
      this.loading.set(true);
    }
    this.error.set(null);

    try {
      const page = await firstValueFrom(
        this.api.getIncidentsPage({
          environmentId,
          status,
          severity,
          cursor,
          limit: this.pageSize,
        })
      );
      if (append) {
        this.hasLoadedExtraPages = true;
        const existingIds = new Set(this.incidents().map((i) => i.id));
        const merged = [...this.incidents(), ...page.items.filter((i) => !existingIds.has(i.id))];
        this.incidents.set(merged);
      } else {
        this.incidents.set(page.items);
      }
      this.nextCursor.set(page.nextCursor);
    } catch (err: unknown) {
      this.error.set(toApiErrorMessage(err, 'Failed to load incidents.'));
    } finally {
      this.loading.set(false);
      this.loadingMore.set(false);
    }
  }
}

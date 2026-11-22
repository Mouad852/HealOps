import { DatePipe, DecimalPipe } from '@angular/common';
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
import {
  Subject,
  catchError,
  debounceTime,
  distinctUntilChanged,
  firstValueFrom,
  forkJoin,
  map,
  of,
  switchMap,
} from 'rxjs';
import {
  AllowedMetricName,
  LogQueryResponse,
  MetricRangeResponse,
  TopologyResponse,
  TraceDetailResponse,
  TraceListResponse,
} from '../../core/api/api.models';
import { HealopsApiClient } from '../../core/api/healops-api.client';
import { toApiErrorMessage } from '../../core/api/problem-detail';
import { EnvironmentStore } from '../../core/state/environment.store';
import { MetricsChartComponent } from '../../shared/metrics-chart/metrics-chart.component';
import { ServiceMapComponent } from '../../shared/service-map/service-map.component';
import { StatusBadgeComponent } from '../../shared/status-badge/status-badge.component';

@Component({
  selector: 'app-services',
  standalone: true,
  imports: [
    DatePipe,
    DecimalPipe,
    FormsModule,
    MetricsChartComponent,
    ServiceMapComponent,
    StatusBadgeComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="d-flex flex-column gap-4" aria-label="Services and observability">
      <div class="d-flex flex-wrap justify-content-between align-items-center gap-2">
        <div>
          <h1 class="h3 mb-1 text-white">Services &amp; Unified Observability</h1>
          <p class="text-secondary small mb-0">
            Service topology, allowlisted range metrics, Jaeger v3 traces, and correlated sanitized logs for
            <strong class="text-info">{{ envStore.selectedEnvironmentId() }}</strong>.
          </p>
        </div>

        <div class="d-flex flex-wrap align-items-center gap-2">
          <label for="serviceSelector" class="small text-secondary mb-0">Service</label>
          <input
            id="serviceSelector"
            type="text"
            class="form-control form-control-sm bg-dark text-white border-secondary"
            style="width: 12rem;"
            [ngModel]="selectedService()"
            (ngModelChange)="onServiceInput($event)"
            placeholder="e.g. checkout-api"
          />

          <label for="metricSelector" class="small text-secondary mb-0">Metric</label>
          <select
            id="metricSelector"
            class="form-select form-select-sm bg-dark text-white border-secondary w-auto"
            [ngModel]="selectedMetric()"
            (ngModelChange)="onMetricChange($event)"
          >
            @for (m of allowedMetrics; track m.value) {
              <option [value]="m.value">{{ m.label }}</option>
            }
          </select>

          <button
            type="button"
            class="btn btn-sm btn-outline-info"
            [disabled]="loading()"
            (click)="refreshAll()"
          >
            <i class="bi bi-arrow-clockwise me-1" aria-hidden="true"></i>Refresh
          </button>
        </div>
      </div>

      @if (error(); as err) {
        <div class="alert alert-danger py-2 small mb-0" role="alert">{{ err }}</div>
      }

      <div class="card healops-surface">
        <div class="card-header border-secondary-subtle d-flex justify-content-between align-items-center">
          <h2 class="h6 mb-0 text-white">Service Dependency Map</h2>
          @if (topology(); as topo) {
            <span class="small text-secondary">Source: {{ topo.dataSource }}</span>
          }
        </div>
        <div class="card-body">
          <app-service-map
            [topology]="topology()"
            [selectedService]="selectedService()"
            (serviceSelected)="selectService($event)"
          />
        </div>
      </div>

      <div class="card healops-surface">
        <div class="card-header border-secondary-subtle d-flex flex-wrap justify-content-between align-items-center gap-2">
          <h2 class="h6 mb-0 text-white">
            Metric Time Series: <span class="text-info">{{ selectedMetric() }}</span>
            @if (selectedService()) {
              <span>({{ selectedService() }})</span>
            }
          </h2>
          @if (metricData(); as m) {
            <span class="small text-secondary">
              Step: {{ m.effectiveStepSeconds }}s
              @if (m.downsampled) {
                <span class="badge text-bg-secondary ms-1">Downsampled</span>
              }
              &bull; Source: {{ m.dataSource }}
            </span>
          }
        </div>
        <div class="card-body">
          @if (!selectedService()) {
            <p class="text-secondary small mb-0">Select a service from the topology or enter a service name to load metrics.</p>
          } @else {
            <app-metrics-chart [data]="metricData()" />
          }
        </div>
      </div>

      <div class="row g-4">
        <div class="col-lg-6">
          <div class="card healops-surface h-100">
            <div class="card-header border-secondary-subtle d-flex justify-content-between align-items-center">
              <h2 class="h6 mb-0 text-white">Traces &amp; Critical Path</h2>
              @if (traces(); as tr) {
                <span class="small text-secondary">{{ tr.traces.length }} traces ({{ tr.dataSource }})</span>
              }
            </div>
            <div class="card-body d-flex flex-column gap-3">
              @if ((traces()?.traces?.length ?? 0) === 0) {
                <p class="text-secondary small mb-0">No traces found in this window.</p>
              } @else {
                <div class="table-responsive">
                  <table class="table table-dark table-sm table-hover align-middle mb-0 small" aria-label="Trace summaries">
                    <thead>
                      <tr>
                        <th scope="col">Trace</th>
                        <th scope="col">Root Operation</th>
                        <th scope="col">Duration</th>
                        <th scope="col">Spans (Err)</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (trace of traces()!.traces; track trace.traceId) {
                        <tr [class.table-active]="selectedTraceId() === trace.traceId">
                          <td class="font-monospace">
                            <button
                              type="button"
                              class="btn btn-link btn-sm p-0 text-info text-decoration-none"
                              (click)="loadTraceDetail(trace.traceId)"
                            >
                              {{ trace.traceId.slice(0, 12) }}
                            </button>
                          </td>
                          <td>
                            <div class="fw-semibold text-white">{{ trace.rootService }}</div>
                            <div class="text-secondary">{{ trace.rootOperation }}</div>
                          </td>
                          <td class="font-monospace">{{ trace.durationMs | number: '1.0-1' }} ms</td>
                          <td>
                            {{ trace.spanCount }}
                            @if (trace.errorSpanCount > 0) {
                              <span class="badge text-bg-danger ms-1">{{ trace.errorSpanCount }} err</span>
                            }
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }

              @if (traceDetail(); as detail) {
                <div class="p-3 rounded border border-secondary-subtle bg-dark d-flex flex-column gap-2 small">
                  <div class="d-flex justify-content-between align-items-center">
                    <strong class="text-info font-monospace">Trace {{ detail.traceId }}</strong>
                    <span class="text-secondary">Total: {{ detail.totalDurationMs | number: '1.0-1' }} ms</span>
                  </div>

                  <div>
                    <div class="text-secondary text-uppercase mb-1">Slowest Spans</div>
                    @if (detail.slowestSpans.length === 0) {
                      <p class="text-secondary mb-0">No span timing records available.</p>
                    } @else {
                      <ol class="mb-0 ps-3 d-flex flex-column gap-1">
                        @for (span of detail.slowestSpans; track span.spanId) {
                          <li class="d-flex justify-content-between gap-2">
                            <span>
                              <strong class="text-white">{{ span.service }}</strong>: {{ span.operation }}
                            </span>
                            <span class="font-monospace text-warning">{{ span.durationMs | number: '1.0-1' }} ms</span>
                          </li>
                        }
                      </ol>
                    }
                  </div>

                  @if (detail.criticalPath.length > 0) {
                    <div>
                      <div class="text-secondary text-uppercase mb-1">Critical Path</div>
                      <div class="d-flex flex-wrap align-items-center gap-1">
                        @for (step of detail.criticalPath; track step.spanId; let last = $last) {
                          <span class="badge text-bg-secondary">{{ step.service }}/{{ step.operation }}</span>
                          @if (!last) {
                            <span class="text-secondary" aria-hidden="true">&rarr;</span>
                          }
                        }
                      </div>
                    </div>
                  }
                </div>
              }
            </div>
          </div>
        </div>

        <div class="col-lg-6">
          <div class="card healops-surface h-100">
            <div class="card-header border-secondary-subtle d-flex flex-wrap justify-content-between align-items-center gap-2">
              <h2 class="h6 mb-0 text-white">Correlated Sanitized Logs</h2>
              @if (traceFilterId()) {
                <div class="d-flex align-items-center gap-2 small">
                  <span class="badge text-bg-info font-monospace">trace: {{ traceFilterId()!.slice(0, 12) }}</span>
                  <button type="button" class="btn btn-sm btn-outline-secondary py-0" (click)="clearTraceFilter()">
                    Clear
                  </button>
                </div>
              }
            </div>
            <div class="card-body">
              @if ((logs()?.records?.length ?? 0) === 0) {
                <p class="text-secondary small mb-0">No sanitized logs matched the active filters.</p>
              } @else {
                <ul class="list-unstyled mb-0 d-flex flex-column gap-2 small" aria-label="Sanitized log records">
                  @for (record of logs()!.records; track record.timestamp + ':' + $index) {
                    <li class="p-2 rounded border border-secondary-subtle bg-dark">
                      <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-1">
                        <div class="d-flex align-items-center gap-2">
                          <app-status-badge [value]="record.level" prefix="Log level" />
                          <strong class="text-white">{{ record.service }}</strong>
                          @if (record.redacted) {
                            <span class="badge text-bg-warning">REDACTED</span>
                          }
                        </div>
                        <time class="text-secondary" [attr.datetime]="record.timestamp">
                          {{ record.timestamp | date: 'mediumTime' }}
                        </time>
                      </div>
                      <div class="font-monospace text-light-emphasis text-break">{{ record.message }}</div>
                      @if (record.traceId) {
                        <div class="mt-1">
                          <button
                            type="button"
                            class="btn btn-link btn-sm p-0 font-monospace text-info text-decoration-none"
                            (click)="loadTraceDetail(record.traceId!)"
                          >
                            trace={{ record.traceId }}
                          </button>
                        </div>
                      }
                    </li>
                  }
                </ul>
              }
            </div>
          </div>
        </div>
      </div>
    </section>
  `,
})
export class ServicesComponent {
  readonly envStore = inject(EnvironmentStore);
  private readonly api = inject(HealopsApiClient);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  readonly allowedMetrics: readonly { value: AllowedMetricName; label: string }[] = [
    { value: 'cpu_percent', label: 'CPU Utilization (%)' },
    { value: 'memory_percent', label: 'Memory Utilization (%)' },
    { value: 'error_rate', label: 'HTTP Error Rate (%)' },
    { value: 'latency_ms', label: 'Latency p95 (ms)' },
    { value: 'restarts', label: 'Container Restarts' },
  ];

  readonly selectedService = signal<string>('');
  readonly selectedMetric = signal<AllowedMetricName>('latency_ms');
  readonly selectedTraceId = signal<string | null>(null);
  readonly traceFilterId = signal<string | null>(null);

  readonly topology = signal<TopologyResponse | null>(null);
  readonly metricData = signal<MetricRangeResponse | null>(null);
  readonly traces = signal<TraceListResponse | null>(null);
  readonly traceDetail = signal<TraceDetailResponse | null>(null);
  readonly logs = signal<LogQueryResponse | null>(null);
  readonly loading = signal<boolean>(false);
  readonly error = signal<string | null>(null);

  private readonly serviceInput$ = new Subject<string>();
  private serviceRequestToken = 0;

  constructor() {
    this.route.queryParamMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => {
        const svc = params.get('service');
        const traceId = params.get('traceId');
        if (svc) {
          this.selectedService.set(svc);
        }
        if (traceId) {
          this.selectedTraceId.set(traceId);
          this.traceFilterId.set(traceId);
        }
      });

    this.serviceInput$
      .pipe(
        map((value) => value.trim()),
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((trimmedService) => {
          const envId = this.envStore.selectedEnvironmentId();
          if (!envId) {
            return of(null);
          }
          const token = ++this.serviceRequestToken;
          const svcParam = trimmedService || undefined;
          const traceFilter = this.traceFilterId() ?? undefined;
          const traces$ = this.api
            .getTraces({ environmentId: envId, service: svcParam, limit: 20 })
            .pipe(catchError(() => of(null)));
          const logs$ = this.api
            .getLogs({ environmentId: envId, service: svcParam, traceId: traceFilter, limit: 50 })
            .pipe(catchError(() => of(null)));
          const metric$ = svcParam
            ? this.api
                .getMetricsRange({
                  environmentId: envId,
                  service: svcParam,
                  metric: this.selectedMetric(),
                })
                .pipe(catchError(() => of(null)))
            : of(null);
          return forkJoin({ traces: traces$, logs: logs$, metric: metric$, token: of(token) });
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((result) => {
        if (!result || result.token !== this.serviceRequestToken) {
          return;
        }
        this.traces.set(result.traces);
        this.logs.set(result.logs);
        this.metricData.set(result.metric);
      });

    effect(() => {
      const envId = this.envStore.selectedEnvironmentId();
      if (envId) {
        void this.loadEnvironmentTelemetry(envId);
      }
    });
  }

  onServiceInput(value: string): void {
    this.selectedService.set(value);
    this.serviceInput$.next(value);
  }

  onMetricChange(metric: string): void {
    this.selectedMetric.set(metric as AllowedMetricName);
    const svc = this.selectedService().trim();
    if (svc) {
      void this.loadMetricSeries(this.envStore.selectedEnvironmentId(), svc, this.selectedMetric());
    }
  }

  selectService(service: string): void {
    this.selectedService.set(service);
    void this.loadServiceScopedData(this.envStore.selectedEnvironmentId(), service);
  }

  clearTraceFilter(): void {
    this.traceFilterId.set(null);
    this.selectedTraceId.set(null);
    this.traceDetail.set(null);
    void this.loadLogs(this.envStore.selectedEnvironmentId(), this.selectedService() || undefined, undefined);
  }

  refreshAll(): void {
    void this.loadEnvironmentTelemetry(this.envStore.selectedEnvironmentId());
  }

  async loadTraceDetail(traceId: string): Promise<void> {
    const envId = this.envStore.selectedEnvironmentId();
    this.selectedTraceId.set(traceId);
    this.traceFilterId.set(traceId);
    try {
      const detail = await firstValueFrom(this.api.getTrace(traceId, envId));
      this.traceDetail.set(detail);
      await this.loadLogs(envId, undefined, traceId);
    } catch (err: unknown) {
      this.error.set(toApiErrorMessage(err, 'Unable to load trace detail.'));
    }
  }

  private async loadEnvironmentTelemetry(environmentId: string): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const topo = await firstValueFrom(this.api.getTopology({ environmentId }));
      this.topology.set(topo);

      let svc = this.selectedService().trim();
      if (!svc && topo.nodes.length > 0) {
        svc = topo.nodes[0].service;
        this.selectedService.set(svc);
      }

      await this.loadServiceScopedData(environmentId, svc);

      const traceId = this.selectedTraceId();
      if (traceId) {
        await this.loadTraceDetail(traceId);
      }
    } catch (err: unknown) {
      this.error.set(toApiErrorMessage(err, 'Unable to load observability data.'));
    } finally {
      this.loading.set(false);
    }
  }

  private async loadServiceScopedData(environmentId: string, service: string): Promise<void> {
    const token = ++this.serviceRequestToken;
    const svcParam = service.trim() || undefined;
    const tasks: Promise<unknown>[] = [
      firstValueFrom(this.api.getTraces({ environmentId, service: svcParam, limit: 20 }))
        .then((res) => {
          if (token === this.serviceRequestToken) {
            this.traces.set(res);
          }
        })
        .catch(() => {
          if (token === this.serviceRequestToken) {
            this.traces.set(null);
          }
        }),
      this.loadLogs(environmentId, svcParam, this.traceFilterId() ?? undefined, token),
    ];

    if (svcParam) {
      tasks.push(this.loadMetricSeries(environmentId, svcParam, this.selectedMetric(), token));
    } else {
      this.metricData.set(null);
    }

    await Promise.all(tasks);
  }

  private async loadMetricSeries(
    environmentId: string,
    service: string,
    metric: AllowedMetricName,
    token = this.serviceRequestToken
  ): Promise<void> {
    try {
      const range = await firstValueFrom(
        this.api.getMetricsRange({ environmentId, service, metric })
      );
      if (token === this.serviceRequestToken) {
        this.metricData.set(range);
      }
    } catch (err: unknown) {
      if (token === this.serviceRequestToken) {
        this.error.set(toApiErrorMessage(err, 'Unable to load metric series.'));
      }
    }
  }

  private async loadLogs(
    environmentId: string,
    service?: string,
    traceId?: string,
    token = this.serviceRequestToken
  ): Promise<void> {
    try {
      const res = await firstValueFrom(
        this.api.getLogs({ environmentId, service, traceId, limit: 50 })
      );
      if (token === this.serviceRequestToken) {
        this.logs.set(res);
      }
    } catch {
      if (token === this.serviceRequestToken) {
        this.logs.set(null);
      }
    }
  }
}

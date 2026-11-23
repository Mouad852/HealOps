import { DatePipe, KeyValuePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  input,
  output,
  viewChild,
} from '@angular/core';
import { EvidenceItemResponse } from '../../core/api/api.models';
import { StatusBadgeComponent } from '../status-badge/status-badge.component';

interface K8sPodRow {
  readonly name: string;
  readonly phase: string;
  readonly ready: string;
  readonly restarts: number;
  readonly cpuPercent: string;
  readonly memoryMb: string;
  readonly reason: string;
}

interface K8sEventRow {
  readonly type: string;
  readonly reason: string;
  readonly message: string;
  readonly target: string;
}

interface ParsedEvidenceView {
  readonly isJson: boolean;
  readonly formattedExcerpt: string;
  readonly k8sSummary: {
    readonly namespace: string;
    readonly workload: string;
    readonly status: string;
    readonly health: string;
    readonly replicasLabel: string | null;
    readonly revisionLabel: string | null;
    readonly pods: readonly K8sPodRow[];
    readonly events: readonly K8sEventRow[];
  } | null;
  readonly keyValuePairs: readonly { readonly key: string; readonly value: string }[];
}

@Component({
  selector: 'app-evidence-dialog',
  standalone: true,
  imports: [DatePipe, KeyValuePipe, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog
      #drawerDialog
      class="evidence-drawer text-light border-start border-secondary p-0"
      aria-labelledby="evidence-drawer-title"
      (cancel)="onDialogCancel($event)"
      (click)="onBackdropClick($event)"
    >
      @if (evidence(); as item) {
        <div class="d-flex flex-column h-100 p-4 gap-3" (click)="$event.stopPropagation()">
          <div class="d-flex justify-content-between align-items-start gap-3 border-bottom border-secondary pb-3">
            <div>
              <div class="d-flex flex-wrap align-items-center gap-2 mb-1">
                <app-status-badge [value]="item.type" prefix="Evidence type" />
                <app-status-badge
                  [value]="item.redactionStatus === 'REDACTED' ? 'WARNING' : 'OK'"
                  [label]="item.redactionStatus === 'REDACTED' ? 'Redacted' : 'Sanitized / Verified'"
                  prefix="Redaction"
                />
              </div>
              <h2 id="evidence-drawer-title" class="h5 mb-0 text-white">{{ item.source }} &bull; {{ item.target }}</h2>
              <div class="small text-secondary font-monospace mt-1">Evidence ID: {{ item.id }}</div>
            </div>
            <button
              #closeButton
              type="button"
              class="btn btn-sm btn-outline-light"
              aria-label="Close evidence details"
              (click)="requestClose()"
            >
              <i class="bi bi-x-lg" aria-hidden="true"></i> Close
            </button>
          </div>

          <div class="healops-subcard p-3">
            <dl class="row small mb-0 g-2">
              <dt class="col-sm-4 text-secondary">Observed At</dt>
              <dd class="col-sm-8 mb-0 text-white">
                <time [attr.datetime]="item.observedAt">{{ item.observedAt | date: 'medium' }}</time>
              </dd>
              <dt class="col-sm-4 text-secondary">Collection Window</dt>
              <dd class="col-sm-8 mb-0">
                <time [attr.datetime]="item.windowStartedAt">{{ item.windowStartedAt | date: 'shortTime' }}</time>
                &ndash;
                <time [attr.datetime]="item.windowEndedAt">{{ item.windowEndedAt | date: 'shortTime' }}</time>
              </dd>
              @if (item.traceId) {
                <dt class="col-sm-4 text-secondary">Trace ID</dt>
                <dd class="col-sm-8 mb-0 font-monospace text-info">{{ item.traceId }}</dd>
              }
              <dt class="col-sm-4 text-secondary">SHA-256 Hash</dt>
              <dd class="col-sm-8 mb-0 font-monospace text-secondary text-truncate" [title]="item.contentHash">
                {{ item.contentHash }}
              </dd>
            </dl>
          </div>

          @if (parsedView(); as view) {
            @if (view.k8sSummary; as k8s) {
              <div class="healops-subcard p-3 d-flex flex-column gap-2">
                <div class="d-flex flex-wrap justify-content-between align-items-center gap-2">
                  <span class="small text-uppercase text-secondary fw-semibold">Kubernetes Workload Snapshot</span>
                  <div class="d-flex align-items-center gap-2">
                    <app-status-badge [value]="k8s.health.toUpperCase()" prefix="Workload health" />
                    <span class="badge text-bg-secondary font-monospace">ns: {{ k8s.namespace }}</span>
                  </div>
                </div>

                <div class="d-flex flex-wrap gap-2">
                  <span class="healops-kv-chip"><strong>workload</strong> {{ k8s.workload }}</span>
                  <span class="healops-kv-chip"><strong>status</strong> {{ k8s.status }}</span>
                  @if (k8s.replicasLabel) {
                    <span class="healops-kv-chip"><strong>replicas</strong> {{ k8s.replicasLabel }}</span>
                  }
                  @if (k8s.revisionLabel) {
                    <span class="healops-kv-chip"><strong>revision</strong> {{ k8s.revisionLabel }}</span>
                  }
                </div>

                @if (k8s.pods.length > 0) {
                  <div class="table-responsive mt-1">
                    <table class="table table-dark table-sm align-middle mb-0 small">
                      <thead>
                        <tr>
                          <th scope="col">Pod</th>
                          <th scope="col">Phase</th>
                          <th scope="col">Ready</th>
                          <th scope="col">Restarts</th>
                          <th scope="col">CPU</th>
                          <th scope="col">Memory</th>
                          <th scope="col">Reason</th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (pod of k8s.pods; track pod.name) {
                          <tr>
                            <td class="font-monospace text-white">{{ pod.name }}</td>
                            <td>{{ pod.phase }}</td>
                            <td class="font-monospace" [class.text-success]="pod.ready === '1/1' || pod.ready === '2/2'" [class.text-warning]="pod.ready.startsWith('0/')">
                              {{ pod.ready }}
                            </td>
                            <td class="font-monospace">{{ pod.restarts }}</td>
                            <td class="font-monospace text-info">{{ pod.cpuPercent }}</td>
                            <td class="font-monospace text-info">{{ pod.memoryMb }}</td>
                            <td>
                              @if (pod.reason !== '—') {
                                <span class="badge text-bg-danger font-monospace">{{ pod.reason }}</span>
                              } @else {
                                <span class="text-secondary">—</span>
                              }
                            </td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  </div>
                }

                @if (k8s.events.length > 0) {
                  <div class="mt-1">
                    <div class="small text-warning fw-semibold mb-1">Cluster Warning Events ({{ k8s.events.length }})</div>
                    <ul class="list-unstyled mb-0 d-flex flex-column gap-1 small">
                      @for (ev of k8s.events; track $index) {
                        <li class="p-2 rounded bg-dark border border-warning-subtle">
                          <div class="d-flex justify-content-between">
                            <strong class="text-warning">{{ ev.reason }}</strong>
                            <span class="font-monospace text-secondary">{{ ev.target }}</span>
                          </div>
                          <div class="text-light-emphasis text-break">{{ ev.message }}</div>
                        </li>
                      }
                    </ul>
                  </div>
                }
              </div>
            } @else if (view.keyValuePairs.length > 0) {
              <div class="healops-subcard p-3">
                <div class="small text-uppercase text-secondary fw-semibold mb-2">Parsed Signal Fields</div>
                <div class="d-flex flex-wrap gap-2">
                  @for (pair of view.keyValuePairs; track pair.key) {
                    <span class="healops-kv-chip">
                      <strong>{{ pair.key }}</strong> {{ pair.value }}
                    </span>
                  }
                </div>
              </div>
            }

            <div>
              <h3 class="h6 text-secondary text-uppercase small mb-2">
                {{ view.isJson ? 'Formatted Telemetry Payload (JSON)' : 'Bounded Log / Text Excerpt' }}
              </h3>
              <pre class="healops-pre mb-0">{{ view.formattedExcerpt }}</pre>
            </div>
          }

          @if ((item.metadata | keyvalue).length > 0) {
            <div>
              <h3 class="h6 text-secondary text-uppercase small mb-2">Provenance Metadata</h3>
              <div class="d-flex flex-wrap gap-2">
                @for (entry of item.metadata | keyvalue; track entry.key) {
                  <span class="healops-kv-chip">
                    <strong>{{ entry.key }}</strong> {{ entry.value }}
                  </span>
                }
              </div>
            </div>
          }
        </div>
      }
    </dialog>
  `,
  styles: [`
    .evidence-drawer {
      margin: 0 0 0 auto;
      height: 100vh;
      max-height: 100vh;
      width: min(640px, 100vw);
      background: #131a26;
      overflow-y: auto;
    }
    .evidence-drawer::backdrop {
      background: rgba(0, 0, 0, 0.72);
    }
  `],
})
export class EvidenceDialogComponent {
  readonly evidence = input<EvidenceItemResponse | null>(null);
  readonly closed = output<void>();

  readonly parsedView = computed<ParsedEvidenceView | null>(() => {
    const item = this.evidence();
    if (!item) {
      return null;
    }
    const raw = (item.excerpt ?? '').trim();
    if (raw.startsWith('{') || raw.startsWith('[')) {
      try {
        const parsed: unknown = JSON.parse(raw);
        const formattedExcerpt = JSON.stringify(parsed, null, 2);
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
          const obj = parsed as Record<string, unknown>;
          if (Array.isArray(obj['pods']) || typeof obj['workload'] === 'string') {
            return {
              isJson: true,
              formattedExcerpt,
              k8sSummary: this.extractK8sSummary(obj),
              keyValuePairs: [],
            };
          }
          const pairs: { key: string; value: string }[] = [];
          for (const [k, v] of Object.entries(obj)) {
            if (v !== null && v !== undefined && typeof v !== 'object') {
              pairs.push({ key: k, value: String(v) });
            }
          }
          return {
            isJson: true,
            formattedExcerpt,
            k8sSummary: null,
            keyValuePairs: pairs,
          };
        }
        return { isJson: true, formattedExcerpt, k8sSummary: null, keyValuePairs: [] };
      } catch {
        // Fall through to plain text
      }
    }
    return {
      isJson: false,
      formattedExcerpt: raw,
      k8sSummary: null,
      keyValuePairs: [],
    };
  });

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('drawerDialog');
  private readonly closeBtnRef = viewChild<ElementRef<HTMLButtonElement>>('closeButton');
  private previouslyFocused: HTMLElement | null = null;

  constructor() {
    effect(() => {
      const item = this.evidence();
      const dialog = this.dialogRef()?.nativeElement;
      if (!dialog) {
        return;
      }
      if (item) {
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
          queueMicrotask(() => this.closeBtnRef()?.nativeElement.focus());
        }
      } else if (dialog.open) {
        dialog.close();
        this.previouslyFocused?.focus();
        this.previouslyFocused = null;
      }
    });
  }

  onDialogCancel(event: Event): void {
    event.preventDefault();
    this.requestClose();
  }

  onBackdropClick(event: MouseEvent): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && event.target === dialog) {
      this.requestClose();
    }
  }

  requestClose(): void {
    this.closed.emit();
  }

  private extractK8sSummary(obj: Record<string, unknown>): ParsedEvidenceView['k8sSummary'] {
    const namespace = typeof obj['namespace'] === 'string' ? obj['namespace'] : 'default';
    const workload = typeof obj['workload'] === 'string' ? obj['workload'] : 'workload';
    const status = typeof obj['status'] === 'string' ? obj['status'] : 'unknown';
    const health = typeof obj['health'] === 'string' ? obj['health'] : 'unknown';

    let replicasLabel: string | null = null;
    let revisionLabel: string | null = null;
    const dep = obj['deployment'];
    if (dep && typeof dep === 'object' && !Array.isArray(dep)) {
      const depObj = dep as Record<string, unknown>;
      const ready = depObj['readyReplicas'] ?? 0;
      const total = depObj['replicas'] ?? 0;
      replicasLabel = `${ready}/${total} ready`;
      if (depObj['revision'] !== undefined) {
        const prev = depObj['previous_revision'];
        revisionLabel = prev !== undefined ? `#${depObj['revision']} (prev #${prev})` : `#${depObj['revision']}`;
      }
    }

    const podsRaw = Array.isArray(obj['pods']) ? obj['pods'] : [];
    const pods: K8sPodRow[] = podsRaw
      .filter((p): p is Record<string, unknown> => Boolean(p && typeof p === 'object'))
      .map((p) => {
        const reasons = Array.isArray(p['reasons']) ? p['reasons'].map(String) : [];
        const reason =
          typeof p['reason'] === 'string' && p['reason']
            ? p['reason']
            : reasons.length > 0
              ? reasons.join(', ')
              : '—';
        return {
          name: typeof p['name'] === 'string' ? p['name'] : 'pod',
          phase: typeof p['phase'] === 'string' ? p['phase'] : 'Unknown',
          ready: typeof p['ready'] === 'string' ? p['ready'] : '—',
          restarts: typeof p['restarts'] === 'number' ? p['restarts'] : 0,
          cpuPercent: typeof p['cpu_percent'] === 'number' ? `${p['cpu_percent']}%` : '—',
          memoryMb: typeof p['memory_mb'] === 'number' ? `${p['memory_mb']} MB` : '—',
          reason,
        };
      });

    const eventsRaw = Array.isArray(obj['events']) ? obj['events'] : [];
    const events: K8sEventRow[] = eventsRaw
      .filter((e): e is Record<string, unknown> => Boolean(e && typeof e === 'object'))
      .map((e) => {
        const inv =
          e['involvedObject'] && typeof e['involvedObject'] === 'object'
            ? (e['involvedObject'] as Record<string, unknown>)
            : {};
        return {
          type: typeof e['type'] === 'string' ? e['type'] : 'Warning',
          reason: typeof e['reason'] === 'string' ? e['reason'] : 'Event',
          message: typeof e['message'] === 'string' ? e['message'] : '',
          target: typeof inv['name'] === 'string' ? inv['name'] : '',
        };
      });

    return {
      namespace,
      workload,
      status,
      health,
      replicasLabel,
      revisionLabel,
      pods,
      events,
    };
  }
}

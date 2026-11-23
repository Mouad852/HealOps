import { DatePipe, JsonPipe, KeyValuePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { ToolExecutionResponse } from '../../core/api/api.models';
import { StatusBadgeComponent } from '../status-badge/status-badge.component';

@Component({
  selector: 'app-tool-progress',
  standalone: true,
  imports: [DatePipe, JsonPipe, KeyValuePipe, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (executions().length === 0) {
      <p class="text-secondary small mb-0">No remediation tool executions recorded for this incident yet.</p>
    } @else {
      <ul class="list-unstyled d-flex flex-column gap-2 mb-0" aria-label="Tool executions">
        @for (exec of executions(); track exec.id) {
          <li class="p-3 healops-subcard d-flex flex-column gap-2">
            <div class="d-flex flex-wrap justify-content-between align-items-center gap-2">
              <div class="d-flex flex-wrap align-items-center gap-2">
                <app-status-badge [value]="exec.status" prefix="Execution status" />
                <span class="badge text-bg-secondary">Attempt #{{ exec.attempt }}</span>
                <span class="small font-monospace text-secondary">key: {{ exec.idempotencyKey.slice(0, 22) }}</span>
              </div>
              <div class="small text-secondary">
                <time [attr.datetime]="exec.startedAt">{{ exec.startedAt | date: 'mediumTime' }}</time>
                @if (exec.endedAt) {
                  <span>
                    &rarr; <time [attr.datetime]="exec.endedAt">{{ exec.endedAt | date: 'mediumTime' }}</time>
                  </span>
                } @else {
                  <span class="ms-2 text-info fw-semibold">(Executing&hellip;)</span>
                }
              </div>
            </div>

            @if ((exec.sanitizedInput | keyvalue).length > 0 || (exec.sanitizedOutput | keyvalue).length > 0) {
              <div class="d-flex flex-wrap gap-1 pt-1">
                @for (entry of exec.sanitizedInput | keyvalue; track entry.key) {
                  <span class="healops-kv-chip">
                    <strong>in.{{ entry.key }}</strong> {{ formatVal(entry.value) }}
                  </span>
                }
                @for (entry of exec.sanitizedOutput | keyvalue; track entry.key) {
                  <span class="healops-kv-chip">
                    <strong>out.{{ entry.key }}</strong> {{ formatVal(entry.value) }}
                  </span>
                }
              </div>
            }

            <details class="mt-1">
              <summary class="small text-info" role="button">View raw execution input &amp; output JSON</summary>
              <div class="row g-2 mt-1">
                <div class="col-md-6">
                  <div class="small text-secondary mb-1">Sanitized Input</div>
                  <pre class="healops-pre mb-0">{{ exec.sanitizedInput | json }}</pre>
                </div>
                <div class="col-md-6">
                  <div class="small text-secondary mb-1">Sanitized Output</div>
                  <pre class="healops-pre mb-0">{{ exec.sanitizedOutput | json }}</pre>
                </div>
              </div>
            </details>
          </li>
        }
      </ul>
    }
  `,
})
export class ToolProgressComponent {
  readonly executions = input.required<readonly ToolExecutionResponse[]>();

  formatVal(val: unknown): string {
    if (val === null || val === undefined) {
      return '—';
    }
    if (typeof val === 'object') {
      return JSON.stringify(val);
    }
    return String(val);
  }
}

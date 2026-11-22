import { DatePipe, JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { ToolExecutionResponse } from '../../core/api/api.models';
import { StatusBadgeComponent } from '../status-badge/status-badge.component';

@Component({
  selector: 'app-tool-progress',
  standalone: true,
  imports: [DatePipe, JsonPipe, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (executions().length === 0) {
      <p class="text-secondary small mb-0">No remediation tool executions recorded for this incident.</p>
    } @else {
      <ul class="list-unstyled d-flex flex-column gap-2 mb-0" aria-label="Tool executions">
        @for (exec of executions(); track exec.id) {
          <li class="p-3 rounded border border-secondary-subtle bg-dark">
            <div class="d-flex flex-wrap justify-content-between align-items-center gap-2">
              <div class="d-flex align-items-center gap-2">
                <app-status-badge [value]="exec.status" prefix="Execution status" />
                <span class="badge text-bg-secondary">Attempt {{ exec.attempt }}</span>
                <span class="small font-monospace text-info">{{ exec.idempotencyKey }}</span>
              </div>
              <div class="small text-secondary">
                Started <time [attr.datetime]="exec.startedAt">{{ exec.startedAt | date: 'mediumTime' }}</time>
                @if (exec.endedAt) {
                  <span>
                    &bull; Ended <time [attr.datetime]="exec.endedAt">{{ exec.endedAt | date: 'mediumTime' }}</time>
                  </span>
                } @else {
                  <span class="ms-2 text-info">(In progress)</span>
                }
              </div>
            </div>

            @if (exec.traceId) {
              <div class="small text-secondary font-monospace mt-1">Trace: {{ exec.traceId }}</div>
            }

            <details class="mt-2">
              <summary class="small text-info" role="button">Sanitized input &amp; output</summary>
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
}

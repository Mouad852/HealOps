import { DatePipe, KeyValuePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { IncidentEventResponse } from '../../core/api/api.models';
import { StatusBadgeComponent } from '../status-badge/status-badge.component';

interface TimelineEntry {
  readonly event: IncidentEventResponse;
  readonly durationLabel: string | null;
}

@Component({
  selector: 'app-incident-timeline',
  standalone: true,
  imports: [DatePipe, KeyValuePipe, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (entries().length === 0) {
      <p class="text-secondary small mb-0">No timeline events recorded yet.</p>
    } @else {
      <ol class="timeline-list list-unstyled mb-0" aria-label="Incident workflow timeline">
        @for (item of entries(); track item.event.id) {
          <li class="timeline-item pb-3 ps-4 position-relative">
            <span class="timeline-marker" aria-hidden="true"></span>
            <div class="d-flex flex-wrap justify-content-between align-items-start gap-2">
              <div class="d-flex flex-wrap align-items-center gap-2">
                <span class="badge text-bg-secondary font-monospace">#{{ item.event.sequence }}</span>
                <strong class="text-white small">{{ item.event.eventType.replaceAll('_', ' ') }}</strong>
                @if (item.event.fromStatus) {
                  <span class="text-secondary small">
                    {{ item.event.fromStatus }} <span aria-hidden="true">&rarr;</span>
                  </span>
                }
                <app-status-badge [value]="item.event.toStatus" prefix="Status" />
              </div>
              <div class="text-secondary small d-flex align-items-center gap-2">
                @if (item.durationLabel) {
                  <span class="badge text-bg-dark border border-secondary-subtle">
                    <i class="bi bi-stopwatch me-1" aria-hidden="true"></i>{{ item.durationLabel }}
                  </span>
                }
                <time [attr.datetime]="item.event.occurredAt">{{ item.event.occurredAt | date: 'medium' }}</time>
              </div>
            </div>

            <div class="mt-1 small text-light-emphasis">
              <span>Actor: <strong class="text-light">{{ item.event.actor }}</strong></span>
              @if (item.event.reason) {
                <span class="ms-2">&bull; {{ item.event.reason }}</span>
              }
            </div>

            @if (item.event.traceId) {
              <div class="mt-1 small text-secondary font-monospace">
                Trace: {{ item.event.traceId }}
              </div>
            }

            @if ((item.event.metadata | keyvalue).length > 0) {
              <div class="mt-1 d-flex flex-wrap gap-1">
                @for (entry of item.event.metadata | keyvalue; track entry.key) {
                  <span class="badge text-bg-dark border border-secondary-subtle font-monospace fw-normal">
                    {{ entry.key }}={{ entry.value }}
                  </span>
                }
              </div>
            }
          </li>
        }
      </ol>
    }
  `,
  styles: [`
    .timeline-list { border-left: 2px solid rgba(173, 181, 189, 0.25); margin-left: 0.5rem; }
    .timeline-marker {
      position: absolute;
      left: -0.42rem;
      top: 0.3rem;
      width: 0.72rem;
      height: 0.72rem;
      border-radius: 50%;
      background: #0dcaf0;
      border: 2px solid #161b22;
    }
  `],
})
export class IncidentTimelineComponent {
  readonly events = input.required<readonly IncidentEventResponse[]>();

  readonly entries = computed<readonly TimelineEntry[]>(() => {
    const sorted = [...this.events()].sort((a, b) => a.sequence - b.sequence);
    return sorted.map((event, index) => {
      if (index === 0) {
        return { event, durationLabel: null };
      }
      const prevMs = Date.parse(sorted[index - 1].occurredAt);
      const currMs = Date.parse(event.occurredAt);
      if (Number.isNaN(prevMs) || Number.isNaN(currMs) || currMs < prevMs) {
        return { event, durationLabel: null };
      }
      return { event, durationLabel: formatDuration(currMs - prevMs) };
    });
  });
}

function formatDuration(ms: number): string {
  if (ms < 1000) {
    return `${ms}ms`;
  }
  const seconds = ms / 1000;
  if (seconds < 60) {
    return `${seconds.toFixed(1)}s`;
  }
  const minutes = Math.floor(seconds / 60);
  const remSeconds = Math.round(seconds % 60);
  return `${minutes}m ${remSeconds}s`;
}

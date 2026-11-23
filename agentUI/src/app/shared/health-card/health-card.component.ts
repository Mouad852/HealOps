import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ServiceHealthCard } from '../../core/api/api.models';
import { StatusBadgeComponent } from '../status-badge/status-badge.component';

@Component({
  selector: 'app-health-card',
  standalone: true,
  imports: [DatePipe, RouterLink, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article
      class="card healops-surface healops-kpi h-100"
      [class.healops-kpi--success]="card().healthStatus === 'HEALTHY'"
      [class.healops-kpi--warning]="card().healthStatus === 'DEGRADED'"
      [class.healops-kpi--danger]="card().healthStatus === 'UNHEALTHY' || card().healthStatus === 'CRITICAL'"
      [attr.aria-label]="'Service health for ' + card().service"
    >
      <div class="card-body d-flex flex-column gap-2">
        <div class="d-flex justify-content-between align-items-start gap-2">
          <div>
            <h3 class="h6 mb-1 fw-bold text-white">{{ card().service }}</h3>
            <span class="badge text-bg-dark border border-secondary-subtle font-monospace">env: {{ card().environmentId }}</span>
          </div>
          <app-status-badge [value]="card().healthStatus" prefix="Health status" />
        </div>

        <div class="d-flex justify-content-between align-items-center small pt-2 border-top border-secondary-subtle">
          <span class="text-secondary">
            Active incidents:
            <strong class="ms-1" [class.text-warning]="card().activeIncidentCount > 0" [class.text-white]="card().activeIncidentCount === 0">
              {{ card().activeIncidentCount }}
            </strong>
          </span>
          @if (card().highestSeverity) {
            <app-status-badge [value]="card().highestSeverity!" prefix="Highest severity" />
          } @else {
            <span class="text-success small"><i class="bi bi-check-lg me-1" aria-hidden="true"></i>No active alerts</span>
          }
        </div>

        <div class="d-flex justify-content-between align-items-center mt-auto pt-2 small text-secondary">
          <span>
            Updated
            <time [attr.datetime]="card().lastUpdatedAt">{{ card().lastUpdatedAt | date: 'shortTime' }}</time>
          </span>
          <div class="d-flex gap-2">
            <a
              class="btn btn-sm btn-outline-info py-0 px-2"
              [routerLink]="['/services']"
              [queryParams]="{ service: card().service }"
            >
              Telemetry <i class="bi bi-arrow-right-short" aria-hidden="true"></i>
            </a>
          </div>
        </div>
      </div>
    </article>
  `,
})
export class HealthCardComponent {
  readonly card = input.required<ServiceHealthCard>();
}

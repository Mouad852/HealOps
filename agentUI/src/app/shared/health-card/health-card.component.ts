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
    <article class="card healops-surface h-100" [attr.aria-label]="'Service health for ' + card().service">
      <div class="card-body d-flex flex-column gap-2">
        <div class="d-flex justify-content-between align-items-start gap-2">
          <div>
            <h3 class="h6 mb-1 fw-semibold text-white">{{ card().service }}</h3>
            <span class="text-secondary small font-monospace">{{ card().environmentId }}</span>
          </div>
          <app-status-badge [value]="card().healthStatus" prefix="Health status" />
        </div>

        <div class="d-flex justify-content-between align-items-center small pt-2 border-top border-secondary-subtle">
          <span class="text-secondary">
            Active incidents:
            <strong class="text-white ms-1">{{ card().activeIncidentCount }}</strong>
          </span>
          @if (card().highestSeverity) {
            <app-status-badge [value]="card().highestSeverity!" prefix="Highest severity" />
          } @else {
            <span class="text-secondary">No active alerts</span>
          }
        </div>

        <div class="d-flex justify-content-between align-items-center mt-auto pt-2 small text-secondary">
          <span>
            Updated
            <time [attr.datetime]="card().lastUpdatedAt">{{ card().lastUpdatedAt | date: 'short' }}</time>
          </span>
          <a
            class="link-info text-decoration-none fw-medium"
            [routerLink]="['/services']"
            [queryParams]="{ service: card().service }"
          >
            Inspect <i class="bi bi-arrow-right-short" aria-hidden="true"></i>
          </a>
        </div>
      </div>
    </article>
  `,
})
export class HealthCardComponent {
  readonly card = input.required<ServiceHealthCard>();
}

import { DatePipe, DecimalPipe, KeyValuePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { CreateIncidentRequest, IncidentSeverity } from '../../core/api/api.models';
import { HealopsApiClient } from '../../core/api/healops-api.client';
import { toApiErrorMessage } from '../../core/api/problem-detail';
import { AuthStore } from '../../core/state/auth.store';
import { DashboardStore } from '../../core/state/dashboard.store';
import { EnvironmentStore } from '../../core/state/environment.store';
import { HealthCardComponent } from '../../shared/health-card/health-card.component';
import { StatusBadgeComponent } from '../../shared/status-badge/status-badge.component';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    DatePipe,
    DecimalPipe,
    FormsModule,
    KeyValuePipe,
    RouterLink,
    HealthCardComponent,
    StatusBadgeComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css',
})
export class DashboardComponent {
  readonly dashboardStore = inject(DashboardStore);
  readonly envStore = inject(EnvironmentStore);
  readonly authStore = inject(AuthStore);
  private readonly api = inject(HealopsApiClient);
  private readonly router = inject(Router);

  readonly severities: readonly IncidentSeverity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

  readonly alertService = signal<string>('checkout-api');
  readonly alertSeverity = signal<IncidentSeverity>('HIGH');
  readonly alertSource = signal<string>('manual-demo');
  readonly alertSummary = signal<string>('High error rate and elevated p95 latency detected');
  readonly alertContainer = signal<string>('');
  readonly submittingAlert = signal<boolean>(false);
  readonly alertError = signal<string | null>(null);
  readonly alertCreatedId = signal<string | null>(null);

  refresh(): void {
    void this.dashboardStore.refreshOverview();
  }

  formatMs(value: number | null | undefined): string {
    if (value === null || value === undefined || Number.isNaN(value)) {
      return 'N/A';
    }
    if (value < 1000) {
      return `${Math.round(value)} ms`;
    }
    const seconds = value / 1000;
    if (seconds < 60) {
      return `${seconds.toFixed(1)} s`;
    }
    return `${(seconds / 60).toFixed(1)} min`;
  }

  async submitDemoAlert(): Promise<void> {
    if (!this.authStore.canWrite() || this.submittingAlert()) {
      return;
    }
    const service = this.alertService().trim();
    if (!service) {
      this.alertError.set('Target service name is required.');
      return;
    }

    this.submittingAlert.set(true);
    this.alertError.set(null);
    this.alertCreatedId.set(null);

    const envId = this.envStore.selectedEnvironmentId();
    const labels: Record<string, string> = { service };
    const containerName = this.alertContainer().trim();
    if (containerName) {
      labels['container'] = containerName;
    }

    const request: CreateIncidentRequest = {
      source: this.alertSource().trim() || 'manual-demo',
      fingerprint: `demo-${envId}-${service}-${Date.now()}`,
      environmentId: envId,
      severity: this.alertSeverity(),
      service,
      startedAt: new Date().toISOString(),
      labels,
      annotations: {
        summary: this.alertSummary().trim() || `Manual demo alert for ${service}`,
      },
    };

    try {
      const created = await firstValueFrom(this.api.createIncident(request));
      this.alertCreatedId.set(created.id);
      await this.dashboardStore.refreshOverview(envId, true);
    } catch (err: unknown) {
      this.alertError.set(toApiErrorMessage(err, 'Failed to submit manual alert.'));
    } finally {
      this.submittingAlert.set(false);
    }
  }

  openCreatedIncident(): void {
    const id = this.alertCreatedId();
    if (id) {
      void this.router.navigate(['/incidents', id]);
    }
  }
}

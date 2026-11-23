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
  readonly alertNamespace = signal<string>('');
  readonly alertDeployment = signal<string>('');
  readonly alertLatencyMs = signal<string>('');
  readonly submittingAlert = signal<boolean>(false);
  readonly alertError = signal<string | null>(null);
  readonly alertCreatedId = signal<string | null>(null);

  refresh(): void {
    void this.dashboardStore.refreshOverview();
  }

  formatActionLabel(actionType: string): string {
    switch (actionType) {
      case 'ROLLBACK_K8S_DEPLOYMENT':
        return 'Rollback K8s Deployment';
      case 'SCALE_K8S_DEPLOYMENT':
        return 'Scale K8s Deployment';
      case 'RESTART_K8S_WORKLOAD':
        return 'Restart K8s Workload';
      case 'RESTART_CONTAINER':
        return 'Restart Docker Container';
      case 'RESTART_OPENSTACK_SERVICE':
        return 'Restart OpenStack Service';
      default:
        return actionType.replace(/_/g, ' ');
    }
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
      labels['runtime'] = 'docker';
    }
    const ns = this.alertNamespace().trim();
    if (ns) {
      labels['namespace'] = ns;
    }
    const dep = this.alertDeployment().trim();
    if (dep) {
      labels['deployment'] = dep;
    }

    const annotations: Record<string, string> = {
      summary: this.alertSummary().trim() || `Manual demo alert for ${service}`,
    };
    const latency = this.alertLatencyMs().trim();
    if (latency) {
      annotations['latency_ms'] = latency.endsWith('ms') ? latency : `${latency}ms`;
    }

    const request: CreateIncidentRequest = {
      source: this.alertSource().trim() || 'manual-demo',
      fingerprint: `demo-${envId}-${service}-${Date.now()}`,
      environmentId: envId,
      severity: this.alertSeverity(),
      service,
      startedAt: new Date().toISOString(),
      labels,
      annotations,
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

  applyDemoWorkloadPreset(): void {
    this.alertService.set('demo-workload');
    this.alertSeverity.set('HIGH');
    this.alertSource.set('manual-demo');
    this.alertContainer.set('healops-demo-workload');
    this.alertNamespace.set('');
    this.alertDeployment.set('');
    this.alertLatencyMs.set('');
    this.alertSummary.set('Demo workload fault injected: elevated error rate and latency');
    this.alertError.set(null);
    this.alertCreatedId.set(null);
  }

  applyPitstopRollbackPreset(): void {
    this.alertService.set('customer');
    this.alertSeverity.set('HIGH');
    this.alertSource.set('alertmanager');
    this.alertContainer.set('');
    this.alertNamespace.set('pitstop');
    this.alertDeployment.set('customer');
    this.alertLatencyMs.set('');
    this.alertSummary.set('Pitstop customer deployment failing after rollout');
    this.alertError.set(null);
    this.alertCreatedId.set(null);
  }

  applyPitstopLatencyPreset(): void {
    this.alertService.set('customer');
    this.alertSeverity.set('MEDIUM');
    this.alertSource.set('alertmanager');
    this.alertContainer.set('');
    this.alertNamespace.set('pitstop');
    this.alertDeployment.set('customer');
    this.alertLatencyMs.set('1650ms');
    this.alertSummary.set('High request latency on Pitstop customer service');
    this.alertError.set(null);
    this.alertCreatedId.set(null);
  }

  openCreatedIncident(): void {
    const id = this.alertCreatedId();
    if (id) {
      void this.router.navigate(['/incidents', id]);
    }
  }
}

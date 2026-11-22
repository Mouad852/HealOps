import { Injectable, OnDestroy, computed, effect, inject, signal, untracked } from '@angular/core';
import { Subscription, firstValueFrom, throttleTime } from 'rxjs';
import {
  DashboardEventEnvelope,
  DashboardOverviewResponse,
} from '../api/api.models';
import { HealopsApiClient } from '../api/healops-api.client';
import { toApiErrorMessage } from '../api/problem-detail';
import { DashboardEventsService } from '../events/dashboard-events.service';
import { EnvironmentStore } from './environment.store';

@Injectable({ providedIn: 'root' })
export class DashboardStore implements OnDestroy {
  private readonly api = inject(HealopsApiClient);
  private readonly envStore = inject(EnvironmentStore);
  private readonly sse = inject(DashboardEventsService);

  private readonly _overview = signal<DashboardOverviewResponse | null>(null);
  private readonly _loading = signal<boolean>(false);
  private readonly _error = signal<string | null>(null);
  private readonly _recentEvents = signal<readonly DashboardEventEnvelope[]>([]);
  private readonly _liveAnnouncement = signal<string>('');

  readonly overview = this._overview.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();
  readonly recentEvents = this._recentEvents.asReadonly();
  readonly liveAnnouncement = this._liveAnnouncement.asReadonly();
  readonly connectionState = this.sse.connectionState;

  readonly pendingApprovalCount = computed(() => this._overview()?.pendingApprovalCount ?? 0);
  readonly activeIncidentCount = computed(() => this._overview()?.incidents.activeCount ?? 0);
  readonly totalIncidentCount = computed(() => this._overview()?.incidents.totalCount ?? 0);
  readonly serviceHealth = computed(() => this._overview()?.serviceHealth ?? []);
  readonly remediationAnalytics = computed(() => this._overview()?.remediationAnalytics ?? null);

  private readonly subscriptions = new Subscription();
  private readonly _started = signal<boolean>(false);

  constructor() {
    effect(() => {
      const started = this._started();
      const envId = this.envStore.selectedEnvironmentId();
      if (!started || !envId) {
        return;
      }
      untracked(() => {
        this._recentEvents.set([]);
        this.sse.connect(envId);
        void this.refreshOverview(envId);
      });
    });

    this.subscriptions.add(
      this.sse.events$.subscribe((envelope) => {
        this._recentEvents.update((list) => [envelope, ...list].slice(0, 25));
        if (
          envelope.type === 'incident.updated' ||
          envelope.type === 'approval.created' ||
          envelope.type === 'approval.updated' ||
          envelope.type === 'service-health.updated'
        ) {
          const activeEnv = this.envStore.selectedEnvironmentId();
          if (activeEnv && envelope.environmentId === activeEnv) {
            void this.refreshOverview(activeEnv, true);
          }
        }
      })
    );

    this.subscriptions.add(
      this.sse
        .ofType('incident.updated', 'approval.created', 'approval.updated', 'tool-execution.updated')
        .pipe(throttleTime(3000, undefined, { leading: true, trailing: true }))
        .subscribe((envelope) => {
          this._liveAnnouncement.set(this.describeEventForScreenReader(envelope));
        })
    );
  }

  start(): void {
    if (this._started()) {
      return;
    }
    this._started.set(true);
  }

  async refreshOverview(environmentId?: string, silent = false): Promise<void> {
    const envId = environmentId ?? this.envStore.selectedEnvironmentId();
    if (!envId) {
      return;
    }
    if (!silent) {
      this._loading.set(true);
    }
    this._error.set(null);
    try {
      const data = await firstValueFrom(this.api.getOverview(envId));
      if (envId === this.envStore.selectedEnvironmentId()) {
        this._overview.set(data);
      }
    } catch (err: unknown) {
      if (envId === this.envStore.selectedEnvironmentId()) {
        this._error.set(toApiErrorMessage(err, 'Unable to load dashboard overview.'));
      }
    } finally {
      if (!silent) {
        this._loading.set(false);
      }
    }
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    this.sse.disconnect();
  }

  private describeEventForScreenReader(envelope: DashboardEventEnvelope): string {
    const payload = envelope.payload;
    switch (envelope.type) {
      case 'incident.updated': {
        const status = typeof payload['status'] === 'string' ? payload['status'] : 'updated';
        const service = typeof payload['service'] === 'string' ? payload['service'] : 'service';
        return `Incident update for ${service}: status is now ${status}.`;
      }
      case 'approval.created': {
        const actionType = typeof payload['actionType'] === 'string' ? payload['actionType'] : 'remediation';
        return `New approval required for ${actionType} in ${envelope.environmentId}.`;
      }
      case 'approval.updated': {
        const decision = typeof payload['decision'] === 'string' ? payload['decision'] : 'updated';
        const actionType = typeof payload['actionType'] === 'string' ? payload['actionType'] : 'action';
        return `Approval ${decision.toLowerCase()} for ${actionType}.`;
      }
      case 'tool-execution.updated': {
        const status = typeof payload['status'] === 'string' ? payload['status'] : 'updated';
        return `Tool execution status changed to ${status}.`;
      }
      default:
        return `Dashboard event ${envelope.type} received.`;
    }
  }
}

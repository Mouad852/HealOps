import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { EnvironmentResponse } from '../api/api.models';
import { HealopsApiClient } from '../api/healops-api.client';
import { toApiErrorMessage } from '../api/problem-detail';

const SELECTED_ENV_STORAGE_KEY = 'healops.selectedEnvironmentId';

@Injectable({ providedIn: 'root' })
export class EnvironmentStore {
  private readonly api = inject(HealopsApiClient);

  private readonly _environments = signal<readonly EnvironmentResponse[]>([]);
  private readonly _selectedEnvironmentId = signal<string>(this.readInitialEnvironmentId());
  private readonly _loading = signal<boolean>(false);
  private readonly _error = signal<string | null>(null);

  readonly environments = this._environments.asReadonly();
  readonly selectedEnvironmentId = this._selectedEnvironmentId.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  readonly selectedEnvironment = computed<EnvironmentResponse | null>(() => {
    const id = this._selectedEnvironmentId();
    return this._environments().find((env) => env.id === id) ?? null;
  });

  readonly isProductionSelected = computed<boolean>(() => {
    const selected = this.selectedEnvironment();
    const id = this._selectedEnvironmentId().toLowerCase();
    return (
      id.includes('prod') ||
      (selected !== null &&
        (selected.name.toLowerCase().includes('prod') ||
          selected.kind.toLowerCase().includes('prod')))
    );
  });

  constructor() {
    effect(() => {
      const envId = this._selectedEnvironmentId();
      if (envId && typeof localStorage !== 'undefined') {
        try {
          localStorage.setItem(SELECTED_ENV_STORAGE_KEY, envId);
        } catch {
          // Ignore storage quota / privacy mode restrictions
        }
      }
    });
  }

  async loadEnvironments(): Promise<readonly EnvironmentResponse[]> {
    this._loading.set(true);
    this._error.set(null);
    try {
      const envs = await firstValueFrom(this.api.getEnvironments());
      this._environments.set(envs);
      const current = this._selectedEnvironmentId();
      if (envs.length > 0 && !envs.some((env) => env.id === current)) {
        this._selectedEnvironmentId.set(envs[0].id);
      }
      return envs;
    } catch (err: unknown) {
      this._error.set(toApiErrorMessage(err, 'Unable to load authorized environments.'));
      return [];
    } finally {
      this._loading.set(false);
    }
  }

  selectEnvironment(environmentId: string): void {
    const trimmed = environmentId.trim();
    if (!trimmed || trimmed === this._selectedEnvironmentId()) {
      return;
    }
    this._selectedEnvironmentId.set(trimmed);
  }

  private readInitialEnvironmentId(): string {
    if (typeof localStorage !== 'undefined') {
      try {
        const stored = localStorage.getItem(SELECTED_ENV_STORAGE_KEY);
        if (stored && stored.trim().length > 0) {
          return stored.trim();
        }
      } catch {
        // Ignore storage read errors
      }
    }
    return 'local';
  }
}

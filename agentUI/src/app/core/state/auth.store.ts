import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { CurrentUserResponse } from '../api/api.models';
import { HealopsApiClient } from '../api/healops-api.client';
import { toApiErrorMessage } from '../api/problem-detail';

@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly api = inject(HealopsApiClient);

  private readonly _user = signal<CurrentUserResponse | null>(null);
  private readonly _loading = signal<boolean>(false);
  private readonly _error = signal<string | null>(null);

  readonly user = this._user.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  readonly username = computed(() => this._user()?.username ?? 'anonymous');
  readonly authorities = computed<readonly string[]>(() => this._user()?.authorities ?? []);

  readonly roleLabel = computed(() => {
    const auths = this.authorities();
    if (auths.includes('ROLE_OPERATOR')) {
      return 'Operator';
    }
    if (auths.includes('ROLE_VIEWER')) {
      return 'Viewer';
    }
    return this._user() ? 'Authenticated' : 'Unauthenticated';
  });

  readonly canRead = computed(() => this.hasAuthority('incident:read'));
  readonly canWrite = computed(() => this.hasAuthority('incident:write'));
  readonly canApprove = computed(() => this.hasAuthority('incident:approve'));
  readonly canPublishRunbook = computed(() => this.hasAuthority('runbook:publish'));

  readonly authorizedEnvironmentIds = computed<readonly string[]>(() =>
    this.authorities()
      .filter((auth) => auth.startsWith('env:') && auth.length > 4)
      .map((auth) => auth.slice(4))
  );

  hasAuthority(authority: string): boolean {
    return this.authorities().includes(authority);
  }

  canAccessEnvironment(environmentId: string): boolean {
    return this.authorities().includes(`env:${environmentId}`);
  }

  async loadCurrentUser(): Promise<CurrentUserResponse | null> {
    this._loading.set(true);
    this._error.set(null);
    try {
      const user = await firstValueFrom(this.api.getCurrentUser());
      this._user.set(user);
      return user;
    } catch (err: unknown) {
      this._error.set(toApiErrorMessage(err, 'Unable to load authenticated session.'));
      return null;
    } finally {
      this._loading.set(false);
    }
  }

  setUserForTesting(user: CurrentUserResponse | null): void {
    this._user.set(user);
  }
}

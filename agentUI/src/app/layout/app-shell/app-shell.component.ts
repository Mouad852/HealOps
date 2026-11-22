import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthStore } from '../../core/state/auth.store';
import { DashboardStore } from '../../core/state/dashboard.store';
import { EnvironmentStore } from '../../core/state/environment.store';
import { StatusBadgeComponent } from '../../shared/status-badge/status-badge.component';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app-shell.component.html',
  styleUrl: './app-shell.component.css',
})
export class AppShellComponent implements OnInit {
  readonly authStore = inject(AuthStore);
  readonly envStore = inject(EnvironmentStore);
  readonly dashboardStore = inject(DashboardStore);

  ngOnInit(): void {
    void this.bootstrap();
  }

  onEnvironmentChange(event: Event): void {
    const target = event.target as HTMLSelectElement | null;
    if (target?.value) {
      this.envStore.selectEnvironment(target.value);
    }
  }

  private async bootstrap(): Promise<void> {
    await Promise.all([
      this.authStore.loadCurrentUser(),
      this.envStore.loadEnvironments(),
    ]);
    this.dashboardStore.start();
  }
}

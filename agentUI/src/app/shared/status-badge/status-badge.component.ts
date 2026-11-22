import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export type BadgeTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

@Component({
  selector: 'app-status-badge',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="healops-badge" [class]="'healops-badge healops-badge--' + resolvedTone()" [attr.aria-label]="ariaLabel()">
      <i [class]="'bi ' + resolvedIcon()" aria-hidden="true"></i>
      <span>{{ displayText() }}</span>
    </span>
  `,
  styles: [`
    .healops-badge {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      padding: 0.2rem 0.55rem;
      border-radius: 999px;
      font-size: 0.75rem;
      font-weight: 600;
      letter-spacing: 0.02em;
      border: 1px solid transparent;
      white-space: nowrap;
    }
    .healops-badge--success { background: rgba(25, 135, 84, 0.18); color: #75e0a7; border-color: rgba(25, 135, 84, 0.45); }
    .healops-badge--warning { background: rgba(255, 193, 7, 0.18); color: #ffd966; border-color: rgba(255, 193, 7, 0.45); }
    .healops-badge--danger { background: rgba(220, 53, 69, 0.2); color: #ff8a96; border-color: rgba(220, 53, 69, 0.5); }
    .healops-badge--info { background: rgba(13, 202, 240, 0.18); color: #7de2f7; border-color: rgba(13, 202, 240, 0.45); }
    .healops-badge--neutral { background: rgba(173, 181, 189, 0.16); color: #ced4da; border-color: rgba(173, 181, 189, 0.4); }
  `],
})
export class StatusBadgeComponent {
  readonly value = input.required<string>();
  readonly label = input<string | null>(null);
  readonly prefix = input<string>('');

  readonly displayText = computed(() => {
    const custom = this.label();
    if (custom) {
      return custom;
    }
    return this.value().replace(/_/g, ' ');
  });

  readonly ariaLabel = computed(() => {
    const pre = this.prefix();
    return pre ? `${pre}: ${this.displayText()}` : this.displayText();
  });

  readonly resolvedTone = computed<BadgeTone>(() => {
    const normalized = this.value().toUpperCase();
    if (
      normalized === 'HEALTHY' ||
      normalized === 'CLOSED' ||
      normalized === 'SUCCEEDED' ||
      normalized === 'APPROVED' ||
      normalized === 'EXECUTED' ||
      normalized === 'PUBLISHED' ||
      normalized === 'CONNECTED' ||
      normalized === 'LOW' ||
      normalized === 'OK'
    ) {
      return 'success';
    }
    if (
      normalized === 'DEGRADED' ||
      normalized === 'AWAITING_APPROVAL' ||
      normalized === 'PROPOSED' ||
      normalized === 'PENDING' ||
      normalized === 'ROLLING_BACK' ||
      normalized === 'ROLLED_BACK' ||
      normalized === 'MEDIUM' ||
      normalized === 'HIGH' ||
      normalized === 'RECONNECTING' ||
      normalized === 'CONNECTING' ||
      normalized === 'DRAFT' ||
      normalized === 'WARNING' ||
      normalized === 'WARN'
    ) {
      return 'warning';
    }
    if (
      normalized === 'CRITICAL' ||
      normalized === 'UNHEALTHY' ||
      normalized === 'FAILED' ||
      normalized === 'REJECTED' ||
      normalized === 'TIMED_OUT' ||
      normalized === 'ERROR' ||
      normalized === 'COLLECTION_ERROR' ||
      normalized === 'DISCONNECTED'
    ) {
      return 'danger';
    }
    if (
      normalized === 'ALERTED' ||
      normalized === 'GATHERING_EVIDENCE' ||
      normalized === 'CORRELATING_SIGNALS' ||
      normalized === 'DIAGNOSING' ||
      normalized === 'EXECUTING' ||
      normalized === 'VERIFYING' ||
      normalized === 'RUNNING' ||
      normalized === 'INFO'
    ) {
      return 'info';
    }
    return 'neutral';
  });

  readonly resolvedIcon = computed<string>(() => {
    const normalized = this.value().toUpperCase();
    switch (this.resolvedTone()) {
      case 'success':
        return 'bi-check-circle-fill';
      case 'warning':
        return normalized === 'RECONNECTING' || normalized === 'CONNECTING'
          ? 'bi-arrow-repeat'
          : 'bi-exclamation-triangle-fill';
      case 'danger':
        return 'bi-x-octagon-fill';
      case 'info':
        return 'bi-activity';
      default:
        return 'bi-dash-circle';
    }
  });
}

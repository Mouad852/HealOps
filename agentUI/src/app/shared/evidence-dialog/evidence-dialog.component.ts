import { DatePipe, KeyValuePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  effect,
  input,
  output,
  viewChild,
} from '@angular/core';
import { EvidenceItemResponse } from '../../core/api/api.models';
import { StatusBadgeComponent } from '../status-badge/status-badge.component';

@Component({
  selector: 'app-evidence-dialog',
  standalone: true,
  imports: [DatePipe, KeyValuePipe, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog
      #drawerDialog
      class="evidence-drawer text-light border-start border-secondary p-0"
      aria-labelledby="evidence-drawer-title"
      (cancel)="onDialogCancel($event)"
      (click)="onBackdropClick($event)"
    >
      @if (evidence(); as item) {
        <div class="d-flex flex-column h-100 p-4 gap-3" (click)="$event.stopPropagation()">
          <div class="d-flex justify-content-between align-items-start gap-3 border-bottom border-secondary pb-3">
            <div>
              <div class="d-flex align-items-center gap-2 mb-1">
                <app-status-badge [value]="item.type" prefix="Evidence type" />
                <app-status-badge
                  [value]="item.redactionStatus === 'REDACTED' ? 'WARNING' : 'OK'"
                  [label]="item.redactionStatus === 'REDACTED' ? 'Redacted' : 'No redaction needed'"
                  prefix="Redaction"
                />
              </div>
              <h2 id="evidence-drawer-title" class="h5 mb-0 text-white">{{ item.source }} &bull; {{ item.target }}</h2>
              <div class="small text-secondary font-monospace mt-1">ID: {{ item.id }}</div>
            </div>
            <button
              #closeButton
              type="button"
              class="btn btn-sm btn-outline-light"
              aria-label="Close evidence details"
              (click)="requestClose()"
            >
              <i class="bi bi-x-lg" aria-hidden="true"></i> Close
            </button>
          </div>

          <dl class="row small mb-0 g-2">
            <dt class="col-sm-4 text-secondary">Observed At</dt>
            <dd class="col-sm-8 mb-0">
              <time [attr.datetime]="item.observedAt">{{ item.observedAt | date: 'medium' }}</time>
            </dd>
            <dt class="col-sm-4 text-secondary">Collection Window</dt>
            <dd class="col-sm-8 mb-0">
              <time [attr.datetime]="item.windowStartedAt">{{ item.windowStartedAt | date: 'short' }}</time>
              &ndash;
              <time [attr.datetime]="item.windowEndedAt">{{ item.windowEndedAt | date: 'short' }}</time>
            </dd>
            <dt class="col-sm-4 text-secondary">Content Hash</dt>
            <dd class="col-sm-8 mb-0 font-monospace text-break">{{ item.contentHash }}</dd>
            @if (item.traceId) {
              <dt class="col-sm-4 text-secondary">Trace ID</dt>
              <dd class="col-sm-8 mb-0 font-monospace">{{ item.traceId }}</dd>
            }
          </dl>

          <div>
            <h3 class="h6 text-secondary text-uppercase small">Bounded Excerpt</h3>
            <pre class="healops-pre mb-0">{{ item.excerpt }}</pre>
          </div>

          @if ((item.metadata | keyvalue).length > 0) {
            <div>
              <h3 class="h6 text-secondary text-uppercase small">Structured Metadata</h3>
              <table class="table table-dark table-sm table-bordered mb-0 small">
                <thead>
                  <tr>
                    <th scope="col">Key</th>
                    <th scope="col">Value</th>
                  </tr>
                </thead>
                <tbody>
                  @for (entry of item.metadata | keyvalue; track entry.key) {
                    <tr>
                      <td class="font-monospace text-info">{{ entry.key }}</td>
                      <td class="font-monospace text-break">{{ entry.value }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </div>
      }
    </dialog>
  `,
  styles: [`
    .evidence-drawer {
      margin: 0 0 0 auto;
      height: 100vh;
      max-height: 100vh;
      width: min(560px, 100vw);
      background: #161b22;
      overflow-y: auto;
    }
    .evidence-drawer::backdrop {
      background: rgba(0, 0, 0, 0.65);
    }
  `],
})
export class EvidenceDialogComponent {
  readonly evidence = input<EvidenceItemResponse | null>(null);
  readonly closed = output<void>();

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('drawerDialog');
  private readonly closeBtnRef = viewChild<ElementRef<HTMLButtonElement>>('closeButton');
  private previouslyFocused: HTMLElement | null = null;

  constructor() {
    effect(() => {
      const item = this.evidence();
      const dialog = this.dialogRef()?.nativeElement;
      if (!dialog) {
        return;
      }
      if (item) {
        if (!dialog.open) {
          this.previouslyFocused =
            typeof document !== 'undefined' && document.activeElement instanceof HTMLElement
              ? document.activeElement
              : null;
          if (typeof dialog.showModal === 'function') {
            dialog.showModal();
          } else {
            dialog.setAttribute('open', '');
          }
          queueMicrotask(() => this.closeBtnRef()?.nativeElement.focus());
        }
      } else if (dialog.open) {
        dialog.close();
        this.previouslyFocused?.focus();
        this.previouslyFocused = null;
      }
    });
  }

  onDialogCancel(event: Event): void {
    event.preventDefault();
    this.requestClose();
  }

  onBackdropClick(event: MouseEvent): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && event.target === dialog) {
      this.requestClose();
    }
  }

  requestClose(): void {
    this.closed.emit();
  }
}

import { DecimalPipe } from '@angular/common';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  effect,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import cytoscape, { Core, ElementDefinition } from 'cytoscape';
import { TopologyResponse } from '../../core/api/api.models';
import { StatusBadgeComponent } from '../status-badge/status-badge.component';

type GraphLayoutName = 'breadthfirst' | 'cose';

@Component({
  selector: 'app-service-map',
  standalone: true,
  imports: [DecimalPipe, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="d-flex flex-column gap-3">
      <div class="d-flex flex-wrap justify-content-between align-items-center gap-2">
        <div class="small text-secondary">
          Layout:
          <div class="btn-group btn-group-sm ms-1" role="group" aria-label="Service map layout selector">
            <button
              type="button"
              class="btn"
              [class.btn-info]="layoutName() === 'breadthfirst'"
              [class.btn-outline-secondary]="layoutName() !== 'breadthfirst'"
              (click)="setLayout('breadthfirst')"
            >
              Hierarchy
            </button>
            <button
              type="button"
              class="btn"
              [class.btn-info]="layoutName() === 'cose'"
              [class.btn-outline-secondary]="layoutName() !== 'cose'"
              (click)="setLayout('cose')"
            >
              Force (COSE)
            </button>
          </div>
        </div>
        @if (selectedService()) {
          <span class="small text-info">Selected service: <strong>{{ selectedService() }}</strong></span>
        }
      </div>

      <div
        #graphContainer
        class="service-map-canvas rounded border border-secondary-subtle"
        role="img"
        [attr.aria-label]="'Service dependency graph with ' + (topology()?.nodes?.length ?? 0) + ' services and ' + (topology()?.edges?.length ?? 0) + ' dependencies'"
      ></div>

      <div class="row g-3">
        <div class="col-lg-6">
          <h4 class="h6 text-secondary text-uppercase small">Services (Keyboard-Accessible Index)</h4>
          @if ((topology()?.nodes?.length ?? 0) === 0) {
            <p class="text-secondary small mb-0">No service nodes discovered for this window.</p>
          } @else {
            <div class="table-responsive">
              <table class="table table-dark table-sm table-hover align-middle mb-0 small" aria-label="Service nodes table">
                <thead>
                  <tr>
                    <th scope="col">Service</th>
                    <th scope="col">Health</th>
                    <th scope="col">Incidents</th>
                    <th scope="col">Error %</th>
                  </tr>
                </thead>
                <tbody>
                  @for (node of topology()!.nodes; track node.id) {
                    <tr [class.table-active]="selectedService() === node.service">
                      <td>
                        <button
                          type="button"
                          class="btn btn-link btn-sm p-0 text-start text-info text-decoration-none fw-semibold"
                          (click)="selectService(node.service)"
                        >
                          {{ node.service }}
                        </button>
                      </td>
                      <td><app-status-badge [value]="node.healthStatus" prefix="Health" /></td>
                      <td>{{ node.activeIncidentCount }}</td>
                      <td class="font-monospace">{{ node.errorRatePercent | number: '1.0-2' }}%</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </div>

        <div class="col-lg-6">
          <h4 class="h6 text-secondary text-uppercase small">Service Dependencies</h4>
          @if ((topology()?.edges?.length ?? 0) === 0) {
            <p class="text-secondary small mb-0">No service-to-service edges recorded in traces.</p>
          } @else {
            <div class="table-responsive">
              <table class="table table-dark table-sm table-hover align-middle mb-0 small" aria-label="Service dependency edges table">
                <thead>
                  <tr>
                    <th scope="col">Caller</th>
                    <th scope="col">Target</th>
                    <th scope="col">Calls</th>
                    <th scope="col">Errors</th>
                    <th scope="col">Avg Latency</th>
                  </tr>
                </thead>
                <tbody>
                  @for (edge of topology()!.edges; track edge.sourceService + '->' + edge.targetService) {
                    <tr
                      [class.table-active]="
                        selectedService() === edge.sourceService || selectedService() === edge.targetService
                      "
                    >
                      <td>
                        <button
                          type="button"
                          class="btn btn-link btn-sm p-0 text-light text-decoration-none"
                          (click)="selectService(edge.sourceService)"
                        >
                          {{ edge.sourceService }}
                        </button>
                      </td>
                      <td>
                        <button
                          type="button"
                          class="btn btn-link btn-sm p-0 text-info text-decoration-none"
                          (click)="selectService(edge.targetService)"
                        >
                          {{ edge.targetService }}
                        </button>
                      </td>
                      <td>{{ edge.callCount }}</td>
                      <td [class.text-danger]="edge.errorCount > 0">{{ edge.errorCount }}</td>
                      <td class="font-monospace">{{ edge.avgLatencyMs | number: '1.0-1' }} ms</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </div>
      </div>
    </div>
  `,
  styles: [`
    .service-map-canvas {
      width: 100%;
      height: 360px;
      background: #0d1117;
    }
  `],
})
export class ServiceMapComponent implements AfterViewInit, OnDestroy {
  readonly topology = input<TopologyResponse | null>(null);
  readonly selectedService = input<string | null>(null);
  readonly serviceSelected = output<string>();

  readonly layoutName = signal<GraphLayoutName>('breadthfirst');

  private readonly containerRef = viewChild<ElementRef<HTMLDivElement>>('graphContainer');
  private cy: Core | null = null;
  private viewReady = false;

  constructor() {
    effect(() => {
      const data = this.topology();
      const layout = this.layoutName();
      if (this.viewReady) {
        this.renderGraph(data, layout);
      }
    });

    effect(() => {
      const selected = this.selectedService();
      this.highlightSelectedService(selected);
    });
  }

  ngAfterViewInit(): void {
    this.viewReady = true;
    this.renderGraph(this.topology(), this.layoutName());
  }

  ngOnDestroy(): void {
    this.destroyGraph();
  }

  setLayout(layout: GraphLayoutName): void {
    this.layoutName.set(layout);
  }

  selectService(service: string): void {
    this.serviceSelected.emit(service);
  }

  private renderGraph(data: TopologyResponse | null, layout: GraphLayoutName): void {
    const container = this.containerRef()?.nativeElement;
    if (!container) {
      return;
    }

    this.destroyGraph();

    const elements: ElementDefinition[] = [];
    if (data) {
      const nodeIds = new Set<string>();
      for (const node of data.nodes) {
        nodeIds.add(node.service);
        elements.push({
          data: {
            id: node.service,
            service: node.service,
            label: `${node.service}\n(${node.healthStatus})`,
            healthStatus: node.healthStatus.toUpperCase(),
          },
        });
      }
      for (const edge of data.edges) {
        if (!nodeIds.has(edge.sourceService)) {
          nodeIds.add(edge.sourceService);
          elements.push({
            data: {
              id: edge.sourceService,
              service: edge.sourceService,
              label: edge.sourceService,
              healthStatus: 'UNKNOWN',
            },
          });
        }
        if (!nodeIds.has(edge.targetService)) {
          nodeIds.add(edge.targetService);
          elements.push({
            data: {
              id: edge.targetService,
              service: edge.targetService,
              label: edge.targetService,
              healthStatus: 'UNKNOWN',
            },
          });
        }
        elements.push({
          data: {
            id: `${edge.sourceService}->${edge.targetService}`,
            source: edge.sourceService,
            target: edge.targetService,
            label: `${edge.callCount} calls (${Math.round(edge.avgLatencyMs)}ms)`,
            hasError: edge.errorCount > 0 ? 'yes' : 'no',
          },
        });
      }
    }

    this.cy = cytoscape({
      container,
      elements,
      style: [
        {
          selector: 'node',
          style: {
            'background-color': '#1f6feb',
            color: '#f0f6fc',
            label: 'data(label)',
            'text-valign': 'center',
            'text-halign': 'center',
            'text-wrap': 'wrap',
            'font-size': '11px',
            width: '120px',
            height: '44px',
            shape: 'round-rectangle',
            'border-width': 2,
            'border-color': '#58a6ff',
          },
        },
        {
          selector: 'node[healthStatus = "HEALTHY"]',
          style: { 'background-color': '#1b4721', 'border-color': '#3fb950' },
        },
        {
          selector: 'node[healthStatus = "DEGRADED"]',
          style: { 'background-color': '#5a4300', 'border-color': '#d29922' },
        },
        {
          selector: 'node[healthStatus = "UNHEALTHY"], node[healthStatus = "CRITICAL"]',
          style: { 'background-color': '#67121b', 'border-color': '#f85149' },
        },
        {
          selector: 'node.selected-service',
          style: { 'border-width': 4, 'border-color': '#0dcaf0' },
        },
        {
          selector: 'edge',
          style: {
            width: 2,
            'line-color': '#6e7681',
            'target-arrow-color': '#6e7681',
            'target-arrow-shape': 'triangle',
            'curve-style': 'bezier',
            label: 'data(label)',
            'font-size': '9px',
            color: '#c9d1d9',
            'text-background-color': '#0d1117',
            'text-background-opacity': 0.85,
          },
        },
        {
          selector: 'edge[hasError = "yes"]',
          style: {
            'line-color': '#f85149',
            'target-arrow-color': '#f85149',
          },
        },
      ],
      layout: {
        name: layout,
        animate: false,
        fit: true,
        padding: 24,
      },
    });

    this.cy.on('tap', 'node', (evt) => {
      const svc = evt.target.data('service');
      if (typeof svc === 'string' && svc.length > 0) {
        this.selectService(svc);
      }
    });

    this.highlightSelectedService(this.selectedService());
  }

  private highlightSelectedService(selected: string | null): void {
    if (!this.cy) {
      return;
    }
    this.cy.nodes().removeClass('selected-service');
    if (selected) {
      this.cy
        .nodes()
        .filter((node) => node.data('service') === selected)
        .addClass('selected-service');
    }
  }

  private destroyGraph(): void {
    if (this.cy) {
      this.cy.destroy();
      this.cy = null;
    }
  }
}

import { DatePipe, DecimalPipe } from '@angular/common';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  computed,
  effect,
  input,
  viewChild,
} from '@angular/core';
import {
  CategoryScale,
  Chart,
  ChartConfiguration,
  Legend,
  LineController,
  LineElement,
  LinearScale,
  PointElement,
  Tooltip,
} from 'chart.js';
import { MetricRangeResponse } from '../../core/api/api.models';

Chart.register(
  LineController,
  LineElement,
  PointElement,
  LinearScale,
  CategoryScale,
  Tooltip,
  Legend
);

const SERIES_COLORS = ['#0dcaf0', '#ffc107', '#75e0a7', '#ff8a96', '#a78bfa'];

interface SeriesSummaryRow {
  readonly label: string;
  readonly count: number;
  readonly latest: number;
  readonly min: number;
  readonly max: number;
  readonly average: number;
  readonly latestTimestamp: string;
}

@Component({
  selector: 'app-metrics-chart',
  standalone: true,
  imports: [DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="d-flex flex-column gap-3">
      <div class="chart-wrapper position-relative" role="img" [attr.aria-label]="chartAriaLabel()">
        <canvas #chartCanvas></canvas>
      </div>

      @if (summaryRows().length === 0) {
        <p class="text-secondary small mb-0">No metric data points available for the selected range.</p>
      } @else {
        <div class="table-responsive">
          <table class="table table-dark table-sm table-striped align-middle mb-0 small" aria-label="Metric data summary table">
            <thead>
              <tr>
                <th scope="col">Series</th>
                <th scope="col">Points</th>
                <th scope="col">Latest ({{ unit() }})</th>
                <th scope="col">Min</th>
                <th scope="col">Max</th>
                <th scope="col">Avg</th>
                <th scope="col">Last Sample</th>
              </tr>
            </thead>
            <tbody>
              @for (row of summaryRows(); track row.label) {
                <tr>
                  <td class="fw-medium text-white">{{ row.label }}</td>
                  <td>{{ row.count }}</td>
                  <td class="font-monospace text-info">{{ row.latest | number: '1.0-2' }}</td>
                  <td class="font-monospace">{{ row.min | number: '1.0-2' }}</td>
                  <td class="font-monospace">{{ row.max | number: '1.0-2' }}</td>
                  <td class="font-monospace">{{ row.average | number: '1.0-2' }}</td>
                  <td>
                    <time [attr.datetime]="row.latestTimestamp">{{ row.latestTimestamp | date: 'shortTime' }}</time>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>
  `,
  styles: [`
    .chart-wrapper {
      height: 260px;
      width: 100%;
    }
  `],
})
export class MetricsChartComponent implements AfterViewInit, OnDestroy {
  readonly data = input<MetricRangeResponse | null>(null);

  private readonly canvasRef = viewChild<ElementRef<HTMLCanvasElement>>('chartCanvas');
  private chart: Chart<'line'> | null = null;
  private viewReady = false;

  readonly unit = computed(() => this.data()?.unit ?? '');

  readonly summaryRows = computed<readonly SeriesSummaryRow[]>(() => {
    const response = this.data();
    if (!response) {
      return [];
    }
    return response.series
      .filter((s) => s.points.length > 0)
      .map((series) => {
        const values = series.points.map((p) => p.value);
        const min = Math.min(...values);
        const max = Math.max(...values);
        const sum = values.reduce((acc, v) => acc + v, 0);
        const last = series.points[series.points.length - 1];
        return {
          label: series.label,
          count: values.length,
          latest: last.value,
          min,
          max,
          average: sum / values.length,
          latestTimestamp: last.timestamp,
        };
      });
  });

  readonly chartAriaLabel = computed(() => {
    const response = this.data();
    if (!response) {
      return 'Metric chart with no loaded data.';
    }
    const rows = this.summaryRows();
    if (rows.length === 0) {
      return `${response.metric} chart for ${response.service}: no data points.`;
    }
    const summary = rows
      .map((r) => `${r.label} latest ${r.latest.toFixed(2)} ${response.unit}`)
      .join('; ');
    return `${response.metric} time-series chart for ${response.service}. ${summary}.`;
  });

  constructor() {
    effect(() => {
      const response = this.data();
      if (this.viewReady) {
        this.renderOrUpdateChart(response);
      }
    });
  }

  ngAfterViewInit(): void {
    this.viewReady = true;
    this.renderOrUpdateChart(this.data());
  }

  ngOnDestroy(): void {
    this.destroyChart();
  }

  private renderOrUpdateChart(response: MetricRangeResponse | null): void {
    const canvas = this.canvasRef()?.nativeElement;
    if (!canvas) {
      return;
    }

    const labels = this.buildLabels(response);
    const datasets = (response?.series ?? []).map((series, index) => {
      const color = SERIES_COLORS[index % SERIES_COLORS.length];
      return {
        label: series.label,
        data: series.points.map((p) => p.value),
        borderColor: color,
        backgroundColor: color,
        borderWidth: 2,
        pointRadius: 2,
        tension: 0.2,
        fill: false,
      };
    });

    if (this.chart) {
      this.chart.data.labels = labels;
      this.chart.data.datasets = datasets;
      this.chart.update('none');
      return;
    }

    const ctx = canvas.getContext('2d');
    if (!ctx) {
      return;
    }

    const config: ChartConfiguration<'line'> = {
      type: 'line',
      data: { labels, datasets },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        plugins: {
          legend: {
            labels: { color: '#e6edf3' },
          },
          tooltip: {
            mode: 'index',
            intersect: false,
          },
        },
        scales: {
          x: {
            ticks: { color: '#8b949e', maxTicksLimit: 8 },
            grid: { color: 'rgba(139, 148, 158, 0.15)' },
          },
          y: {
            ticks: { color: '#8b949e' },
            grid: { color: 'rgba(139, 148, 158, 0.15)' },
          },
        },
      },
    };

    this.chart = new Chart(ctx, config);
  }

  private buildLabels(response: MetricRangeResponse | null): string[] {
    const firstSeries = response?.series[0];
    if (!firstSeries) {
      return [];
    }
    return firstSeries.points.map((point) => {
      const parsed = new Date(point.timestamp);
      return Number.isNaN(parsed.getTime())
        ? point.timestamp
        : parsed.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    });
  }

  private destroyChart(): void {
    if (this.chart) {
      this.chart.destroy();
      this.chart = null;
    }
  }
}

import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-runbooks',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="card bg-dark text-white border-secondary shadow-sm">
      <div class="card-header border-secondary d-flex justify-content-between align-items-center">
        <h4 class="mb-0"><i class="bi bi-journal-code me-2 text-info"></i>Automated Runbooks</h4>
        <button class="btn btn-sm btn-outline-info"><i class="bi bi-plus-lg"></i> New Runbook</button>
      </div>
      <div class="card-body">
        <div class="row g-3">
          <div class="col-md-6">
            <div class="card bg-secondary text-white border-0 p-3">
              <div class="d-flex justify-content-between align-items-start mb-2">
                <h5 class="card-title mb-0">High CPU Container Restart</h5>
                <span class="badge bg-success">PUBLISHED (v1)</span>
              </div>
              <p class="card-text text-light small mb-2">Automatically identifies and restarts Docker containers showing sustained CPU anomalies above 95% threshold.</p>
              <div class="text-muted small">Target: Docker &bull; Matched Incidents: 14</div>
            </div>
          </div>
          <div class="col-md-6">
            <div class="card bg-secondary text-white border-0 p-3">
              <div class="d-flex justify-content-between align-items-start mb-2">
                <h5 class="card-title mb-0">Kubernetes Pod Scale on Latency</h5>
                <span class="badge bg-success">PUBLISHED (v1)</span>
              </div>
              <p class="card-text text-light small mb-2">Scales deployment replicas within max boundary [2, 10] when p99 latency exceeds 1.5s.</p>
              <div class="text-muted small">Target: Kubernetes &bull; Matched Incidents: 8</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  `
})
export class RunbooksComponent implements OnInit {
  runbooks = signal<any[]>([]);

  constructor(private http: HttpClient) {}

  ngOnInit() {}
}

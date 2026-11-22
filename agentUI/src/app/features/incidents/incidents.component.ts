import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-incidents',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="card bg-dark text-white border-secondary shadow-sm">
      <div class="card-header border-secondary d-flex justify-content-between align-items-center">
        <h4 class="mb-0"><i class="bi bi-shield-shaded me-2 text-primary"></i>Incidents Management</h4>
        <button class="btn btn-outline-secondary btn-sm" (click)="loadIncidents()"><i class="bi bi-arrow-clockwise"></i> Refresh</button>
      </div>
      <div class="card-body p-0">
        <div *ngIf="loading()" class="text-center py-4"><div class="spinner-border text-primary"></div></div>
        <div *ngIf="!loading() && incidents().length === 0" class="text-center py-5 text-muted">
          No incidents on record.
        </div>
        <table *ngIf="!loading() && incidents().length > 0" class="table table-dark table-hover mb-0">
          <thead>
            <tr>
              <th>ID</th>
              <th>Severity</th>
              <th>Service</th>
              <th>Status</th>
              <th>Started At</th>
              <th>Fingerprint</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let inc of incidents()">
              <td class="font-monospace small">{{ inc.id.substring(0, 8) }}...</td>
              <td><span class="badge bg-danger">{{ inc.severity }}</span></td>
              <td class="fw-bold">{{ inc.service }}</td>
              <td><span class="badge bg-secondary">{{ inc.status }}</span></td>
              <td class="small text-muted">{{ inc.startedAt | date:'medium' }}</td>
              <td class="small text-muted font-monospace">{{ inc.fingerprint }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  `
})
export class IncidentsComponent implements OnInit {
  incidents = signal<any[]>([]);
  loading = signal(true);

  constructor(private http: HttpClient) {}

  ngOnInit() {
    this.loadIncidents();
  }

  loadIncidents() {
    this.loading.set(true);
    this.http.get<any[]>('/api/v1/incidents?environmentId=local').subscribe({
      next: (res) => {
        this.incidents.set(res || []);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }
}

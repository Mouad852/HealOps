import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
  {
    path: 'dashboard',
    title: 'Dashboard — HealOps Autopilot',
    loadComponent: () =>
      import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent),
  },
  {
    path: 'incidents',
    title: 'Incidents — HealOps Autopilot',
    loadComponent: () =>
      import('./features/incidents/incidents.component').then((m) => m.IncidentsComponent),
  },
  {
    path: 'incidents/:incidentId',
    title: 'Incident Detail — HealOps Autopilot',
    loadComponent: () =>
      import('./features/incidents/incident-detail.component').then((m) => m.IncidentDetailComponent),
  },
  {
    path: 'approvals',
    title: 'Approvals — HealOps Autopilot',
    loadComponent: () =>
      import('./features/approvals/approvals.component').then((m) => m.ApprovalsComponent),
  },
  {
    path: 'services',
    title: 'Services & Observability — HealOps Autopilot',
    loadComponent: () =>
      import('./features/services/services.component').then((m) => m.ServicesComponent),
  },
  {
    path: 'chat',
    title: 'Copilot — HealOps Autopilot',
    loadComponent: () =>
      import('./features/chat/chat.component').then((m) => m.ChatComponent),
  },
  {
    path: 'runbooks',
    title: 'Runbooks — HealOps Autopilot',
    loadComponent: () =>
      import('./features/runbooks/runbooks.component').then((m) => m.RunbooksComponent),
  },
  { path: '**', redirectTo: 'dashboard' },
];

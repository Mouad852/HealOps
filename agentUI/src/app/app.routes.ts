import { Routes } from '@angular/router';
import { DashboardComponent } from './features/dashboard/dashboard.component';
import { IncidentsComponent } from './features/incidents/incidents.component';
import { ApprovalsComponent } from './features/approvals/approvals.component';
import { ChatComponent } from './chat/chat.component';
import { RunbooksComponent } from './features/runbooks/runbooks.component';

export const routes: Routes = [
  { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
  { path: 'dashboard', component: DashboardComponent },
  { path: 'incidents', component: IncidentsComponent },
  { path: 'approvals', component: ApprovalsComponent },
  { path: 'chat', component: ChatComponent },
  { path: 'runbooks', component: RunbooksComponent },
  { path: '**', redirectTo: 'dashboard' }
];

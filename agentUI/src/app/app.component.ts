import { Component, signal } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, CommonModule],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent {
  title = 'HealOps Incident Autopilot';
  selectedEnvironment = signal('local');
  environments = [
    { id: 'local', label: 'Local Docker' },
    { id: 'staging', label: 'Staging Cluster' },
    { id: 'production', label: 'Production' }
  ];

  setEnvironment(env: string) {
    this.selectedEnvironment.set(env);
  }
}

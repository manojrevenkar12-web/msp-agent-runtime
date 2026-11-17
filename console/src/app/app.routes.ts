import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./features/overview/overview').then((m) => m.Overview), title: 'Overview' },
  { path: 'runs', loadComponent: () => import('./features/runs/runs-page').then((m) => m.RunsPage), title: 'Runs' },
  { path: 'agents', loadComponent: () => import('./features/agents/agents-page').then((m) => m.AgentsPage), title: 'Agents' },
  { path: 'approvals', loadComponent: () => import('./features/approvals/approvals-page').then((m) => m.ApprovalsPage),
    title: 'Approvals' },
  { path: '**', redirectTo: '' },
];

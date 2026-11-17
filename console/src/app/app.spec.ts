import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { demoFixture } from '../testing/fixture';
import { AgentEditor } from './features/agents/agent-editor';
import { ApprovalsPage } from './features/approvals/approvals-page';
import { Overview } from './features/overview/overview';
import { count, euros, minutes, pct } from './shared/format';
import { StateView } from './shared/state';

async function settle<T>(f: ComponentFixture<T>, http?: HttpTestingController, demo: object = demoFixture) {
  for (let i = 0; i < 4; i++) {
    http?.match('demo.json').forEach((r) => r.flush(demo));
    await f.whenStable();
    f.detectChanges();
  }
}

describe('console', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => sessionStorage.clear());

  it('formats money, counts and durations the way the text reads', () => {
    expect(euros(5_001_000)).toBe('€50,010');
    expect(count(7380)).toBe('7,380');
    expect(pct(1, 8)).toBe('12.5%');
    expect(minutes(75)).toBe('1 h 15 min');
  });

  it('shows the simulated month: the ledger, the switchboard and the comparison', async () => {
    const f = TestBed.createComponent(Overview);
    await settle(f, http);
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toContain('Systemhaus Nord');
    expect(el.querySelector('.ledger')?.textContent).toContain('€2.38');
    expect(el.querySelectorAll('rect.tick').length).toBe(3);
    const cmp = el.querySelector('app-compare table')!;
    expect(cmp.textContent).toContain('€50,000 (18.5%)');
    expect(cmp.textContent).toContain('€7,920 (2.9%)');
  });

  it('says what went wrong and loads again on request', async () => {
    const f = TestBed.createComponent(Overview);
    for (let i = 0; i < 3; i++) {
      f.detectChanges();
      http.match('demo.json').forEach((r) => r.flush('nope', { status: 500, statusText: 'Server Error' }));
      await f.whenStable();
      await new Promise((r) => setTimeout(r, 0));
    }
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    const alert = el.querySelector('[role=alert]');
    expect(alert?.textContent).toContain('could not be loaded');
    expect(alert?.textContent).toContain('demo.json');
    (alert!.querySelector('button') as HTMLButtonElement).click();
    await settle(f, http);
    expect(el.querySelector('h1')?.textContent).toContain('Systemhaus Nord');
  });

  it('removes an approved invoice from the queue', async () => {
    const f = TestBed.createComponent(ApprovalsPage);
    await settle(f, http);
    const el = f.nativeElement as HTMLElement;
    expect(el.textContent).toContain('€2,673');
    (el.querySelector('button.primary') as HTMLButtonElement).click();
    await settle(f, http);
    expect(el.textContent).toContain('Nothing waits for a person');
  });

  it('keeps a setting change as a draft only with a runtime connected', async () => {
    const f = TestBed.createComponent(AgentEditor);
    f.componentRef.setInput('agent', demoFixture.agents[0]);
    f.detectChanges();
    const input = (f.nativeElement as HTMLElement).querySelector('input[type=number]') as HTMLInputElement;
    input.value = '5';
    input.dispatchEvent(new Event('input'));
    f.detectChanges();
    const save = (f.nativeElement as HTMLElement).querySelector('button[type=submit]') as HTMLButtonElement;
    expect(save.disabled).toBe(false);
    expect((f.nativeElement as HTMLElement).textContent).toContain('was 3');
    save.click();
    await f.whenStable();
    f.detectChanges();
    expect((f.nativeElement as HTMLElement).querySelector('[role=status]')?.textContent).toContain('Connect a runtime');
  });

  it('shows the empty state with what happens next', async () => {
    const f = TestBed.createComponent(StateView);
    f.componentRef.setInput('what', 'The runs');
    f.componentRef.setInput('empty', true);
    f.componentRef.setInput('emptyText', 'No runs yet.');
    f.detectChanges();
    expect((f.nativeElement as HTMLElement).textContent).toContain('No runs yet.');
  });
});

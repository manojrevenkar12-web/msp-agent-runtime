import { Injectable, signal } from '@angular/core';

/** The API key of a connected runtime, kept for the browser tab only. Without one the console shows demo data. */
@Injectable({ providedIn: 'root' })
export class Session {
  private static readonly KEY = 'msp-console-key';
  readonly key = signal<string | null>(Session.read());

  private static read(): string | null {
    try {
      return sessionStorage.getItem(Session.KEY);
    } catch {
      return null;
    }
  }

  connect(key: string): void {
    try {
      sessionStorage.setItem(Session.KEY, key);
    } catch {
      // storage unavailable: the key lives for this page only
    }
    this.key.set(key);
  }

  disconnect(): void {
    try {
      sessionStorage.removeItem(Session.KEY);
    } catch {
      // nothing stored
    }
    this.key.set(null);
  }
}

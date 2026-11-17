import { Signal, signal } from '@angular/core';

/** Data a screen waits for: loading, failed (with a message and a retry), or there. */
export interface Loadable<T> {
  readonly loading: Signal<boolean>;
  readonly error: Signal<string | null>;
  readonly value: Signal<T | null>;
  reload(): Promise<void>;
}

export function loadable<T>(fetch: () => Promise<T>): Loadable<T> {
  const loading = signal(true);
  const error = signal<string | null>(null);
  const value = signal<T | null>(null);
  const reload = async () => {
    loading.set(true);
    error.set(null);
    try {
      value.set(await fetch());
    } catch (e) {
      error.set(e instanceof Error ? e.message : 'Something went wrong.');
    } finally {
      loading.set(false);
    }
  };
  void reload();
  return { loading, error, value, reload };
}

// English text, euro amounts: written the way an Irish or British reader expects (€1,234).
const LOCALE = 'en-IE';
const eur = new Intl.NumberFormat(LOCALE, { style: 'currency', currency: 'EUR', maximumFractionDigits: 0 });
const eurExact = new Intl.NumberFormat(LOCALE, { style: 'currency', currency: 'EUR' });
const int = new Intl.NumberFormat(LOCALE);

export const euros = (cents: number) => eur.format(cents / 100);
export const eurosExact = (cents: number) => eurExact.format(cents / 100);
export const count = (n: number) => int.format(Math.round(n));
export const pct = (a: number, b: number) => (b === 0 ? '–' : `${((100 * a) / b).toFixed(1)}%`);

export function minutes(m: number): string {
  if (m < 60) return `${m} min`;
  const h = Math.floor(m / 60);
  const r = m % 60;
  return r === 0 ? `${h} h` : `${h} h ${r} min`;
}

export function seconds(s: number): string {
  if (s < 90) return `${Math.round(s)} s`;
  return `${(s / 60).toFixed(1)} min`;
}

export function when(iso: string): string {
  return new Date(iso).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Berlin' });
}

export const agentName: Record<string, string> = {
  triage: 'Ticket triage',
  'time-capture': 'Time capture',
  billing: 'Monthly billing',
  concierge: 'Customer updates',
};

export const eventName: Record<string, string> = {
  'ticket.created': 'ticket opened',
  'ticket.status_changed': 'status changed',
  'ticket.note_added': 'note written',
  'rmm.session_ended': 'remote session ended',
  'field.checked_out': 'on-site visit ended',
  'erp.month_closed': 'month closed',
};

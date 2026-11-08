/** Date-only API values are local calendar dates, not UTC instants. */
export function formatDate(date: string, locale: string): string {
  const [year, month, day] = date.split('-').map(Number);
  return new Intl.DateTimeFormat(locale, { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: 'UTC' })
    .format(new Date(Date.UTC(year, month - 1, day)));
}

export function formatDateTime(timestamp: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' })
    .format(new Date(timestamp));
}

export function formatRelativeDay(date: string, locale: string, today = new Date()): string {
  const current = [today.getFullYear(), String(today.getMonth() + 1).padStart(2, '0'),
    String(today.getDate()).padStart(2, '0')].join('-');
  const previous = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1);
  const yesterday = [previous.getFullYear(), String(previous.getMonth() + 1).padStart(2, '0'),
    String(previous.getDate()).padStart(2, '0')].join('-');
  if (date === current) return locale.startsWith('de') ? 'Heute' : 'Today';
  if (date === yesterday) return locale.startsWith('de') ? 'Gestern' : 'Yesterday';
  return formatDate(date, locale);
}

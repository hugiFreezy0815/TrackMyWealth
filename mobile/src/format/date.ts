import type { IntlShape } from 'react-intl';

const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;

/** A date-only API value (YYYY-MM-DD) is a calendar date, not an instant: format it in UTC. */
export function formatDate(date: string, locale: string): string {
  const match = DATE_ONLY.exec(date);
  if (!match) throw new TypeError('Expected a YYYY-MM-DD date');
  const [, year, month, day] = match.map(Number);
  return new Intl.DateTimeFormat(locale, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    timeZone: 'UTC',
  }).format(new Date(Date.UTC(year, month - 1, day)));
}

/** An instant (ISO 8601 with offset) in the device's time zone. */
export function formatDateTime(timestamp: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(timestamp),
  );
}

const localDay = (day: Date) =>
  [
    day.getFullYear(),
    String(day.getMonth() + 1).padStart(2, '0'),
    String(day.getDate()).padStart(2, '0'),
  ].join('-');

/** "Today" / "Yesterday" from the catalogue (`date.today`, `date.yesterday`), else the date. */
export function formatRelativeDay(
  date: string,
  intl: Pick<IntlShape, 'locale' | 'formatMessage'>,
  today = new Date(),
): string {
  if (date === localDay(today)) return intl.formatMessage({ id: 'date.today' });
  const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1);
  if (date === localDay(yesterday)) return intl.formatMessage({ id: 'date.yesterday' });
  return formatDate(date, intl.locale);
}

import type { IntlShape } from 'react-intl';

const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;
const UNKNOWN = '–'; // U+2013 EN DASH, as for an unknown amount

/**
 * PO decision 2026-11-09 (#488): English dates read "1 Oct 2026" (day first, month as a word, so
 * nobody misreads 01/10 vs 10/01); German dates read "01.10.2026". Built from formatToParts so the
 * order never depends on which regional locale data an Intl implementation or polyfill ships.
 */
function dateText(date: Date, locale: string, timeZone?: string): string {
  const german = locale.toLowerCase().startsWith('de');
  const parts = new Intl.DateTimeFormat(german ? 'de' : 'en', {
    day: german ? '2-digit' : 'numeric',
    month: german ? '2-digit' : 'short',
    year: 'numeric',
    timeZone,
  }).formatToParts(date);
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((candidate) => candidate.type === type)?.value ?? '';
  return german
    ? `${part('day')}.${part('month')}.${part('year')}`
    : `${part('day')} ${part('month')} ${part('year')}`;
}

/** Response data is an open set: an unreadable value shows "–" instead of failing the screen. */
function unreadable(value: string): string {
  if (process.env.NODE_ENV !== 'production') console.warn('Unreadable API date:', value);
  return UNKNOWN;
}

/** A date-only API value (YYYY-MM-DD) is a calendar date, not an instant: format it in UTC. */
export function formatDate(date: string, locale: string): string {
  const match = DATE_ONLY.exec(date);
  if (!match) return unreadable(date);
  const [, year, month, day] = match.map(Number);
  const utc = new Date(Date.UTC(year, month - 1, day));
  if (utc.getUTCMonth() !== month - 1 || utc.getUTCDate() !== day) return unreadable(date);
  return dateText(utc, locale, 'UTC');
}

/** An instant (ISO 8601 with offset) in the device's time zone, 24-hour time. */
export function formatDateTime(timestamp: string, locale: string): string {
  const instant = new Date(timestamp);
  if (typeof timestamp !== 'string' || Number.isNaN(instant.getTime())) {
    return unreadable(timestamp);
  }
  const time = new Intl.DateTimeFormat(locale.toLowerCase().startsWith('de') ? 'de' : 'en', {
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(instant);
  return `${dateText(instant, locale)}, ${time}`;
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

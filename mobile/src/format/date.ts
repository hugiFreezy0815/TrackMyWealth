import type { IntlShape } from 'react-intl';

const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;
const UNKNOWN = '–'; // U+2013 EN DASH, as for an unknown amount

/**
 * PO decision 2026-11-09 (#488): English dates read "1 Oct 2026" (day first, month as a word, so
 * nobody misreads 01/10 vs 10/01); German dates read "01.10.2026". Built from formatToParts so the
 * order never depends on which regional locale data an Intl implementation or polyfill ships.
 */
function dateText(date: Date, locale: string, timeZone?: string, withinMonth = false): string {
  const german = locale.toLowerCase().startsWith('de');
  const parts = new Intl.DateTimeFormat(german ? 'de' : 'en', {
    day: german ? '2-digit' : 'numeric',
    month: german ? '2-digit' : 'short',
    year: withinMonth ? undefined : 'numeric',
    timeZone,
  }).formatToParts(date);
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((candidate) => candidate.type === type)?.value ?? '';
  const dayMonth = german ? `${part('day')}.${part('month')}.` : `${part('day')} ${part('month')}`;
  if (withinMonth) return dayMonth;
  return german ? `${dayMonth}${part('year')}` : `${dayMonth} ${part('year')}`;
}

/** Response data is an open set: an unreadable value shows "–" instead of failing the screen. */
function unreadable(value: string): string {
  if (process.env.NODE_ENV !== 'production') console.warn('Unreadable API date:', value);
  return UNKNOWN;
}

/** A date-only API value (YYYY-MM-DD) is a calendar date, not an instant: format it in UTC. */
function parseCalendarDate(date: string): Date | null {
  const match = DATE_ONLY.exec(date);
  if (!match) return null;
  const [, year, month, day] = match.map(Number);
  const utc = new Date(Date.UTC(year, month - 1, day));
  return utc.getUTCMonth() === month - 1 && utc.getUTCDate() === day ? utc : null;
}

export function formatDate(date: string, locale: string): string {
  const utc = parseCalendarDate(date);
  return utc ? dateText(utc, locale, 'UTC') : unreadable(date);
}

export function formatBookingMonth(month: string, locale: string): string {
  const utc = parseCalendarDate(`${month}-01`);
  return utc
    ? new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(
        utc,
      )
    : unreadable(month);
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

/** The calendar day of an instant (ISO 8601 with offset) in the device's time zone. */
export function formatInstantDate(timestamp: string, locale: string): string {
  const instant = new Date(timestamp);
  if (typeof timestamp !== 'string' || Number.isNaN(instant.getTime())) {
    return unreadable(timestamp);
  }
  return dateText(instant, locale);
}

const localDay = (day: Date) =>
  [
    day.getFullYear(),
    String(day.getMonth() + 1).padStart(2, '0'),
    String(day.getDate()).padStart(2, '0'),
  ].join('-');

type DateIntl = Pick<IntlShape, 'locale' | 'formatMessage'>;

function relativeDay(date: string, intl: DateIntl, today: Date): string | null {
  if (date === localDay(today)) return intl.formatMessage({ id: 'date.today' });
  const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1);
  return date === localDay(yesterday) ? intl.formatMessage({ id: 'date.yesterday' }) : null;
}

/** "Today" / "Yesterday" from the catalogue, else the full date. */
export function formatRelativeDay(date: string, intl: DateIntl, today = new Date()): string {
  return relativeDay(date, intl, today) ?? formatDate(date, intl.locale);
}

/** Use a compact calendar date under a month heading, retaining the local relative labels. */
export function formatBookingDay(
  date: string,
  intl: DateIntl,
  withinMonth: boolean,
  today = new Date(),
): string {
  if (!withinMonth) return formatDate(date, intl.locale);
  const relative = relativeDay(date, intl, today);
  if (relative) return relative;
  const utc = parseCalendarDate(date);
  return utc ? dateText(utc, intl.locale, 'UTC', true) : unreadable(date);
}

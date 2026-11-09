import { createIntl, createIntlCache } from 'react-intl';

import de from '@/i18n/de.json';
import en from '@/i18n/en.json';
import { formatDate, formatDateTime, formatRelativeDay } from './date';

const deIntl = createIntl({ locale: 'de-CH', messages: { ...en, ...de } }, createIntlCache());
const enIntl = createIntl({ locale: 'en', messages: en }, createIntlCache());

describe('date formatting', () => {
  afterEach(() => jest.restoreAllMocks());

  // PO decision 2026-11-09 (#488): English "1 Oct 2026", German "01.10.2026".
  it.each([
    ['2026-01-01', 'de-CH', '01.01.2026'],
    ['2026-10-01', 'de-DE', '01.10.2026'],
    ['2026-10-01', 'de', '01.10.2026'],
    ['2026-10-01', 'en', '1 Oct 2026'],
    ['2026-12-31', 'en', '31 Dec 2026'],
  ])('formats the calendar day %s in %s as %s in every time zone', (date, locale, expected) => {
    expect(formatDate(date, locale)).toBe(expected);
  });

  it.each(['2026-1-1', '2026-02-30', '2026-13-01', 'yesterday', ''])(
    'shows the unreadable date %j as "–" instead of failing the screen',
    (value) => {
      const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
      expect(formatDate(value, 'en')).toBe('–');
      expect(warn).toHaveBeenCalled();
    },
  );

  it('formats an instant with date and 24-hour time', () => {
    expect(formatDateTime('2026-11-08T09:30:00Z', 'en')).toMatch(/^\d{1,2} Nov 2026, \d{2}:30$/);
    expect(formatDateTime('2026-11-08T09:30:00Z', 'de-CH')).toMatch(/^\d{2}\.11\.2026, \d{2}:30$/);
  });

  it('shows an unreadable instant as "–"', () => {
    jest.spyOn(console, 'warn').mockImplementation(() => {});
    expect(formatDateTime('not a time', 'en')).toBe('–');
  });

  it('says today and yesterday from the catalogue, else the date', () => {
    const today = new Date(2026, 2, 1, 0, 30); // local midnight, the day after Feb 28
    expect(formatRelativeDay('2026-03-01', deIntl, today)).toBe('Heute');
    expect(formatRelativeDay('2026-02-28', deIntl, today)).toBe('Gestern');
    expect(formatRelativeDay('2026-02-28', enIntl, today)).toBe('Yesterday');
    expect(formatRelativeDay('2026-02-27', deIntl, today)).toBe('27.02.2026');
  });
});

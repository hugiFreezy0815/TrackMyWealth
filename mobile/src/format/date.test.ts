import { createIntl, createIntlCache } from 'react-intl';

import de from '@/i18n/de.json';
import en from '@/i18n/en.json';
import { formatDate, formatDateTime, formatRelativeDay } from './date';

const deIntl = createIntl({ locale: 'de-CH', messages: { ...en, ...de } }, createIntlCache());
const enIntl = createIntl({ locale: 'en', messages: en }, createIntlCache());

describe('date formatting', () => {
  it('formats a date-only value as that calendar day in every time zone', () => {
    expect(formatDate('2026-01-01', 'de-CH')).toBe('01.01.2026');
    expect(formatDate('2026-01-01', 'en')).toBe('01/01/2026');
  });

  it('rejects a value that is not YYYY-MM-DD', () => {
    expect(() => formatDate('2026-1-1', 'en')).toThrow(TypeError);
  });

  it('formats an instant with date and time', () => {
    expect(formatDateTime('2026-11-08T09:30:00Z', 'en')).toMatch(/2026/);
  });

  it('says today and yesterday from the catalogue, else the date', () => {
    const today = new Date(2026, 2, 1, 0, 30); // local midnight, the day after Feb 28
    expect(formatRelativeDay('2026-03-01', deIntl, today)).toBe('Heute');
    expect(formatRelativeDay('2026-02-28', deIntl, today)).toBe('Gestern');
    expect(formatRelativeDay('2026-02-28', enIntl, today)).toBe('Yesterday');
    expect(formatRelativeDay('2026-02-27', deIntl, today)).toBe('27.02.2026');
  });
});

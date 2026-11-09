import { createIntl, createIntlCache } from 'react-intl';

import { DEVICE_LOCALE } from './device-intl';
import { resolveLocale } from './index';
import de from './de.json';
import en from './en.json';

describe('locales and ICU messages', () => {
  it('chooses the member language over the device locale', () => {
    expect(resolveLocale('de-CH', 'en-US')).toBe('de-CH');
    expect(resolveLocale('en', 'de-CH')).toBe('en');
  });

  it('falls back to the device locale when the member chose none', () => {
    expect(resolveLocale(null, 'de-DE')).toBe('de-DE');
    expect(resolveLocale('', 'de_CH')).toBe('de-CH');
    expect(resolveLocale(undefined, 'de-AT')).toBe('de');
    expect(resolveLocale(undefined, 'fr-CH')).toBe('en');
  });

  it('reads the device locale from Intl by default', () => {
    expect(DEVICE_LOCALE).toBe(new Intl.DateTimeFormat().resolvedOptions().locale);
    expect(resolveLocale(null)).toBe(resolveLocale(null, DEVICE_LOCALE));
  });

  it('renders singular and plural in German', () => {
    const intl = createIntl({ locale: 'de', messages: de }, createIntlCache());
    expect(intl.formatMessage({ id: 'import.rows' }, { count: 1 })).toBe('1 Buchung');
    expect(intl.formatMessage({ id: 'import.rows' }, { count: 3 })).toBe('3 Buchungen');
  });

  it('has the same keys in every catalogue', () => {
    expect(Object.keys(de).sort()).toEqual(Object.keys(en).sort());
  });
});

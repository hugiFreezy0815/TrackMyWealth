import { createIntl, createIntlCache } from 'react-intl';
import { resolveLocale } from './index';
import de from './de.json';

describe('locales and ICU messages', () => {
  it('chooses the preferred user locale over the device', () => {
    expect(resolveLocale('de-CH', 'en-US')).toBe('de-CH');
    expect(resolveLocale(null, 'de-DE')).toBe('de-DE');
  });
  it('renders singular and plural in German', () => {
    const intl = createIntl({ locale: 'de', messages: de }, createIntlCache());
    expect(intl.formatMessage({ id: 'import.rows' }, { count: 1 })).toBe('1 Buchung');
    expect(intl.formatMessage({ id: 'import.rows' }, { count: 3 })).toBe('3 Buchungen');
  });
});

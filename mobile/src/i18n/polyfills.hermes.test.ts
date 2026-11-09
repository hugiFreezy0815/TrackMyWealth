// Startup under Hermes-like Intl (#529's iOS crash, 2026-11-09). Node has a complete Intl, so
// src/i18n/polyfills.native.test.ts never applies the DateTimeFormat polyfill. On Hermes it is
// applied, and iOS reports UTC as "GMT", a name the polyfill's zone data rejected with a
// RangeError while the module loaded: the release app closed before its first screen.
import { formatDate, formatDateTime } from '@/format/date';
import { defaultTimeZone, loadPolyfillsOnHermesLikeIntl } from './hermes-intl';

describe('Intl polyfills on a Hermes-like engine, device zone GMT', () => {
  it('load without throwing and format in UTC', () => {
    expect(() => loadPolyfillsOnHermesLikeIntl('GMT')).not.toThrow();

    expect(defaultTimeZone()).toBe('UTC'); // Etc/GMT, canonicalized as ECMA-402 requires
    expect(
      new Intl.DateTimeFormat('de-CH', { timeStyle: 'short' }).format(
        new Date('2026-11-09T08:30:00Z'),
      ),
    ).toBe('08:30');
    expect(new Intl.PluralRules('de').select(1)).toBe('one');
    // PO decision 2026-11-09: the date order holds with the polyfill's locale data too.
    expect(formatDate('2026-10-01', 'en')).toBe('1 Oct 2026');
    expect(formatDate('2026-10-01', 'de-CH')).toBe('01.10.2026');
    expect(formatDateTime('2026-11-09T08:30:00Z', 'en')).toBe('9 Nov 2026, 08:30');
  });
});

// Startup under Hermes-like Intl (#529's iOS crash, 2026-11-09). Node has a complete Intl, so
// src/i18n/polyfills.native.test.ts never applies the DateTimeFormat polyfill. On Hermes it is
// applied, and iOS reports UTC as "GMT", a name the polyfill's zone data rejected with a
// RangeError while the module loaded: the release app closed before its first screen.
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
  });
});

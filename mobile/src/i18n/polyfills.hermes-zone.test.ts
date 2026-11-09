// The device's time zone reaches the polyfilled DateTimeFormat (it defaults to UTC otherwise).
import { defaultTimeZone, loadPolyfillsOnHermesLikeIntl } from './hermes-intl';

describe('Intl polyfills on a Hermes-like engine, device zone Europe/Zurich', () => {
  it('format times in the device zone', () => {
    loadPolyfillsOnHermesLikeIntl('Europe/Zurich');

    expect(defaultTimeZone()).toBe('Europe/Zurich');
    expect(
      new Intl.DateTimeFormat('de-CH', { timeStyle: 'short' }).format(
        new Date('2026-11-09T08:30:00Z'),
      ),
    ).toBe('09:30');
  });
});

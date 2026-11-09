/**
 * Test helper: turns this Jest realm's Intl into what Hermes offers (no PluralRules, no
 * DateTimeFormat#formatRange) with the given device time zone, then loads the native polyfills.
 * The polyfills install once per realm, so each scenario needs its own test file.
 */
export function loadPolyfillsOnHermesLikeIntl(deviceTimeZone: string): void {
  delete (Intl as { PluralRules?: unknown }).PluralRules;
  delete (Intl.DateTimeFormat.prototype as { formatRange?: unknown }).formatRange;
  const nativeResolved = Intl.DateTimeFormat.prototype.resolvedOptions;
  Intl.DateTimeFormat.prototype.resolvedOptions = function resolvedOptions() {
    return { ...nativeResolved.call(this), timeZone: deviceTimeZone };
  };
  jest.isolateModules(() => jest.requireActual('./polyfills'));
}

export const defaultTimeZone = () =>
  (Intl.DateTimeFormat as unknown as { getDefaultTimeZone?: () => string }).getDefaultTimeZone?.();

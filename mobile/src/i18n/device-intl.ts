import { Platform } from 'react-native';

// Read once, before src/i18n/polyfills can replace Intl: a polyfilled DateTimeFormat reports
// its own defaults ('en', UTC), not the device's. Import this module before the polyfills.
const native = new Intl.DateTimeFormat().resolvedOptions();

const browserLocale =
  Platform.OS === 'web' && typeof navigator !== 'undefined'
    ? (navigator.languages?.[0] ?? navigator.language)
    : undefined;

/** The device (or browser) locale, e.g. "de-CH". */
export const DEVICE_LOCALE: string = browserLocale || native.locale || 'en';
/** The device's IANA time zone, e.g. "Europe/Zurich". */
export const DEVICE_TIME_ZONE: string = native.timeZone || 'UTC';

/**
 * The decimal separator of the device's number format. The `decimal-pad` keyboard offers this
 * key, whatever language the member chose for the app (#534), so amount input accepts it too.
 */
export const DEVICE_DECIMAL_SEPARATOR: ',' | '.' = deviceDecimalSeparator(DEVICE_LOCALE);

function deviceDecimalSeparator(locale: string): ',' | '.' {
  try {
    return new Intl.NumberFormat(locale).format(1.5).includes(',') ? ',' : '.';
  } catch {
    return '.';
  }
}

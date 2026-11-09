// ADR 0008: Hermes has no Intl.PluralRules, and its NumberFormat turns a DecimalString into a JS
// number (digits lost). Load the FormatJS implementations on iOS/Android; web browsers have
// NumberFormat v3 natively (src/i18n/polyfills.ts). Import order matters: the device's time zone
// is read first, NumberFormat needs PluralRules.
import { DEVICE_TIME_ZONE } from './device-intl';

import '@formatjs/intl-pluralrules/polyfill.js';
import '@formatjs/intl-pluralrules/locale-data/en.js';
import '@formatjs/intl-pluralrules/locale-data/de.js';
// Forced: the exact-decimal formatting of amounts must not depend on the engine's own build.
import '@formatjs/intl-numberformat/polyfill-force.js';
import '@formatjs/intl-numberformat/locale-data/en.js';
import '@formatjs/intl-numberformat/locale-data/de.js';
import '@formatjs/intl-numberformat/locale-data/de-CH.js';
// Only where the engine lacks formatRange/dateStyle; then with time zone data and the device zone.
import '@formatjs/intl-datetimeformat/polyfill.js';
import '@formatjs/intl-datetimeformat/locale-data/en.js';
import '@formatjs/intl-datetimeformat/locale-data/de.js';
import '@formatjs/intl-datetimeformat/locale-data/de-CH.js';
import '@formatjs/intl-datetimeformat/add-golden-tz.js';

type PolyfilledDateTimeFormat = { __setDefaultTimeZone?: (timeZone: string) => void };
(Intl.DateTimeFormat as unknown as PolyfilledDateTimeFormat).__setDefaultTimeZone?.(
  DEVICE_TIME_ZONE,
);

// ADR 0008: Hermes has no Intl.PluralRules or Intl.DisplayNames, and its NumberFormat turns a DecimalString into a JS
// number (digits lost). Load the FormatJS implementations on iOS/Android; web browsers have
// NumberFormat v3 natively (src/i18n/polyfills.ts). Import order matters: the device's time zone
// is read first, NumberFormat needs PluralRules.
import { DEVICE_TIME_ZONE } from './device-intl';
import { applyDeviceTimeZone } from './time-zone';

import '@formatjs/intl-pluralrules/polyfill.js';
import '@formatjs/intl-pluralrules/locale-data/en.js';
import '@formatjs/intl-pluralrules/locale-data/de.js';
import '@formatjs/intl-pluralrules/locale-data/fr.js';
import '@formatjs/intl-pluralrules/locale-data/it.js';
// Forced: the exact-decimal formatting of amounts must not depend on the engine's own build.
import '@formatjs/intl-numberformat/polyfill-force.js';
import '@formatjs/intl-numberformat/locale-data/en.js';
import '@formatjs/intl-numberformat/locale-data/de.js';
import '@formatjs/intl-numberformat/locale-data/de-CH.js';
import '@formatjs/intl-numberformat/locale-data/fr.js';
import '@formatjs/intl-numberformat/locale-data/fr-CH.js';
import '@formatjs/intl-numberformat/locale-data/it.js';
import '@formatjs/intl-numberformat/locale-data/it-CH.js';
// Hermes has no DisplayNames: the ISO currency and country pickers (#491) name every code.
import '@formatjs/intl-displaynames/polyfill.js';
import '@formatjs/intl-displaynames/locale-data/en.js';
import '@formatjs/intl-displaynames/locale-data/de.js';
import '@formatjs/intl-displaynames/locale-data/fr.js';
import '@formatjs/intl-displaynames/locale-data/it.js';
// Only where the engine lacks formatRange/dateStyle; then with time zone data and the device zone.
import '@formatjs/intl-datetimeformat/polyfill.js';
import '@formatjs/intl-datetimeformat/locale-data/en.js';
import '@formatjs/intl-datetimeformat/locale-data/de.js';
import '@formatjs/intl-datetimeformat/locale-data/de-CH.js';
import '@formatjs/intl-datetimeformat/locale-data/fr.js';
import '@formatjs/intl-datetimeformat/locale-data/fr-CH.js';
import '@formatjs/intl-datetimeformat/locale-data/it.js';
import '@formatjs/intl-datetimeformat/locale-data/it-CH.js';
import '@formatjs/intl-datetimeformat/add-golden-tz.js';

type PolyfilledDateTimeFormat = { __setDefaultTimeZone?: (timeZone: string) => void };
const polyfilled = Intl.DateTimeFormat as unknown as PolyfilledDateTimeFormat;
applyDeviceTimeZone(polyfilled.__setDefaultTimeZone?.bind(polyfilled), DEVICE_TIME_ZONE);

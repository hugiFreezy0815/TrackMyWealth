/**
 * ISO code lists for the pickers of S5/S6 (#491) and their names in the app language.
 *
 * The names come from `Intl.DisplayNames` (CLDR): native in browsers and Node, the FormatJS
 * polyfill on Hermes (src/i18n/polyfills.native.ts). Without it, or for a code CLDR has no name
 * for, the code itself is shown: a picker must never hide an option or show an empty label.
 */

/**
 * ISO 4217 currencies in circulation (2026), without funds, metals and test codes. Each is known
 * to the backend's `java.util.Currency` (JRE 25), which validates `@ValidCurrencyCode`.
 */
export const ISO_CURRENCIES: readonly string[] = (
  'AED AFN ALL AMD AOA ARS AUD AWG AZN BAM BBD BDT BHD BIF BMD BND BOB BRL BSD BTN BWP BYN ' +
  'BZD CAD CDF CHF CLP CNY COP CRC CUP CVE CZK DJF DKK DOP DZD EGP ERN ETB EUR FJD FKP GBP ' +
  'GEL GHS GIP GMD GNF GTQ GYD HKD HNL HTG HUF IDR ILS INR IQD IRR ISK JMD JOD JPY KES KGS ' +
  'KHR KMF KPW KRW KWD KYD KZT LAK LBP LKR LRD LSL LYD MAD MDL MGA MKD MMK MNT MOP MRU MUR ' +
  'MVR MWK MXN MYR MZN NAD NGN NIO NOK NPR NZD OMR PAB PEN PGK PHP PKR PLN PYG QAR RON RSD ' +
  'RUB RWF SAR SBD SCR SDG SEK SGD SHP SLE SOS SRD SSP STN SVC SYP SZL THB TJS TMT TND TOP ' +
  'TRY TTD TWD TZS UAH UGX USD UYU UZS VED VES VND VUV WST XAF XCD XCG XOF XPF YER ZAR ZMW ' +
  'ZWG'
).split(' ');

/** The 249 officially assigned ISO 3166-1 alpha-2 country codes. */
export const ISO_COUNTRIES: readonly string[] = (
  'AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ ' +
  'BR BS BT BV BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM ' +
  'DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS ' +
  'GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN ' +
  'KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ ' +
  'MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM ' +
  'PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV ' +
  'SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI ' +
  'VN VU WF WS YE YT ZA ZM ZW'
).split(' ');

type DisplayType = 'currency' | 'region';

// One formatter per language and type: building one per code would cost ~400 constructions for
// a single render of both pickers.
const formatters = new Map<string, Intl.DisplayNames | null>();

function formatter(locale: string, type: DisplayType): Intl.DisplayNames | null {
  const key = `${locale}|${type}`;
  if (!formatters.has(key)) {
    try {
      formatters.set(key, new Intl.DisplayNames([locale], { type, fallback: 'code' }));
    } catch {
      // No DisplayNames in this engine: the codes stay readable.
      formatters.set(key, null);
    }
  }
  return formatters.get(key) ?? null;
}

function displayName(code: string, locale: string, type: DisplayType): string {
  try {
    return formatter(locale, type)?.of(code) ?? code;
  } catch {
    // A code this CLDR version rejects.
    return code;
  }
}

/** "Euro" / "Schweizer Franken"; the code when no name is known. */
export function currencyName(code: string, locale: string): string {
  return displayName(code, locale, 'currency');
}

/** "Switzerland" / "Schweiz"; the code when no name is known (e.g. a newer country code). */
export function countryName(code: string, locale: string): string {
  return displayName(code, locale, 'region');
}

export type IsoOption = { value: string; label: string };

/**
 * Options for a picker: `first` in their given order (the likely choices), then every other code
 * sorted by its name in the app language. Each code appears once.
 */
function options(
  codes: readonly string[],
  first: readonly string[],
  label: (code: string) => string,
  locale: string,
): IsoOption[] {
  const leading = Array.from(new Set(first.filter((code) => code.length > 0)));
  const collator = new Intl.Collator(locale);
  const rest = codes
    .filter((code) => !leading.includes(code))
    .map((code) => ({ value: code, label: label(code) }))
    .sort((a, b) => collator.compare(a.label, b.label));
  return [...leading.map((code) => ({ value: code, label: label(code) })), ...rest];
}

/** "EUR · Euro": the code first, as on statements, then the name (M14 "EUR · Euro"). */
export function currencyLabel(code: string, locale: string): string {
  const name = currencyName(code, locale);
  return name === code ? code : `${code} · ${name}`;
}

export function currencyOptions(locale: string, first: readonly string[] = []): IsoOption[] {
  return options(ISO_CURRENCIES, first, (code) => currencyLabel(code, locale), locale);
}

export function countryOptions(locale: string, first: readonly string[] = []): IsoOption[] {
  return options(ISO_COUNTRIES, first, (code) => countryName(code, locale), locale);
}

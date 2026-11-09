#!/usr/bin/env node
/**
 * UX-03 #378: executable form of the amount display rule in 02-visual-foundations.md section 3.
 * `formatAmount` is the reference algorithm; CASES are the normative strings. CI runs this on
 * every change under docs/ux/ (UX docs CI). The production formatter (#488) must pass the same
 * cases under Jest with the Hermes Intl polyfills (ADR 0008).
 *
 * Run: node docs/ux/check-amount-format.mjs
 * No dependencies. Needs an Intl.NumberFormat that formats decimal strings exactly
 * (NumberFormat v3: Node 19+, current browsers, @formatjs/intl-numberformat).
 */

const MINUS = '−'; // U+2212 MINUS SIGN
const NBSP = ' '; // U+00A0 NO-BREAK SPACE between currency code and number
const HIDDEN = '••••'; // ••••
const UNKNOWN = '–'; // – EN DASH
// House format (PO 2026-11-09): every UI language displays the Swiss grouping, an ASCII
// apostrophe, and a decimal point (1'111'111.00). The locale only shapes the spoken label.
const DISPLAY_GROUP = "'";
const DISPLAY_DECIMAL = '.';
// A plain decimal as the API sends it (BigDecimal#toPlainString): no exponent, no grouping.
const DECIMAL_STRING = /^-?\d+(\.\d+)?$/;

/**
 * @param {object} p
 * @param {string|null} p.value   DecimalString from the API, never a JS number
 * @param {string} p.currency     ISO 4217 code
 * @param {string} p.locale       'de-CH' | 'de-DE' | 'en' ... (does not change the display)
 * @param {'balance'|'change'} [p.signRole] 'change' shows an explicit + on positive values
 * @param {boolean} [p.valueKnown] false for UNKNOWN values
 * @param {boolean} [p.masked]     privacy masking is on
 * @returns {string} the visible text; the accessible label is built from message keys (#379)
 */
export function formatAmount({ value, currency, locale, signRole = 'balance', valueKnown = true, masked = false }) {
  if (masked) return HIDDEN;
  if (!valueKnown || value === null || value === undefined) return UNKNOWN;
  if (typeof value !== 'string' || !DECIMAL_STRING.test(value)) {
    throw new TypeError(`Not a DecimalString: ${JSON.stringify(value)}`);
  }
  // The currency's minor-unit scale (CHF/EUR 2, JPY 0), from CLDR.
  const scale = new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions()
    .maximumFractionDigits;
  const negative = value.startsWith('-');
  // Formatted with 'en' (Latin digits), then the separators are replaced: one display rule.
  const parts = new Intl.NumberFormat('en', {
    style: 'decimal',
    minimumFractionDigits: scale,
    maximumFractionDigits: scale,
    roundingMode: 'halfExpand', // commercial rounding, display only
    useGrouping: true,
  }).formatToParts(negative ? value.slice(1) : value); // string in: no floating point
  const zero = parts.every((part) => !/^(integer|fraction)$/.test(part.type) || /^0+$/.test(part.value));
  const number = parts
    .map((part) =>
      part.type === 'group' ? DISPLAY_GROUP : part.type === 'decimal' ? DISPLAY_DECIMAL : part.value,
    )
    .join('');
  const sign = zero ? '' : negative ? MINUS : signRole === 'change' ? '+' : '';
  return `${sign}${currency}${NBSP}${number}`;
}

// [input, expected visible text]; spaces in expected strings are written as NBSP.
const CASES = [
  // Acceptance criterion of #378: -1234.5 in de-CH / CHF and de-DE / EUR, plus en.
  [{ value: '-1234.5', currency: 'CHF', locale: 'de-CH' }, `${MINUS}CHF${NBSP}1'234.50`],
  [{ value: '-1234.5', currency: 'EUR', locale: 'de-DE' }, `${MINUS}EUR${NBSP}1'234.50`],
  [{ value: '-1234.5', currency: 'CHF', locale: 'en' }, `${MINUS}CHF${NBSP}1'234.50`],
  // PO 2026-11-09: the Swiss grouping in every UI language, millions included.
  [{ value: '1111111', currency: 'CHF', locale: 'en' }, `CHF${NBSP}1'111'111.00`],
  [{ value: '1122', currency: 'EUR', locale: 'de' }, `EUR${NBSP}1'122.00`],
  // Positive balance: no sign. Positive change: explicit plus.
  [{ value: '478160.56', currency: 'CHF', locale: 'de-CH' }, `CHF${NBSP}478'160.56`],
  [{ value: '6842.1', currency: 'CHF', locale: 'de-CH', signRole: 'change' }, `+CHF${NBSP}6'842.10`],
  // Display rounding is half away from zero at the currency scale; the stored value is untouched.
  [{ value: '1234.565', currency: 'CHF', locale: 'de-CH' }, `CHF${NBSP}1'234.57`],
  [{ value: '-1234.565', currency: 'EUR', locale: 'de-DE' }, `${MINUS}EUR${NBSP}1'234.57`],
  [{ value: '1234.564', currency: 'CHF', locale: 'de-CH' }, `CHF${NBSP}1'234.56`],
  [{ value: '-1234.5', currency: 'JPY', locale: 'en' }, `${MINUS}JPY${NBSP}1'235`],
  // A value that rounds to zero carries no sign, also as a change.
  [{ value: '-0.004', currency: 'CHF', locale: 'de-CH' }, `CHF${NBSP}0.00`],
  [{ value: '0', currency: 'EUR', locale: 'de-DE', signRole: 'change' }, `EUR${NBSP}0.00`],
  // Precision beyond a JS number is kept (Number('12345678901234567.89') would print ...568).
  [{ value: '12345678901234567.89', currency: 'CHF', locale: 'de-CH' }, `CHF${NBSP}12'345'678'901'234'567.89`],
  // Masked and unknown never show digits or zero.
  [{ value: '-1234.5', currency: 'CHF', locale: 'de-CH', masked: true }, HIDDEN],
  [{ value: null, currency: 'CHF', locale: 'de-CH' }, UNKNOWN],
  [{ value: '12.30', currency: 'CHF', locale: 'de-CH', valueKnown: false }, UNKNOWN],
];
const REJECTED = [1234.5, '1e3', '1,5', ' 1', '1.', '+1', ''];

let failures = 0;
const show = (s) => JSON.stringify(s).replace(/ /g, '␣');
for (const [input, expected] of CASES) {
  const actual = formatAmount(input);
  const pass = actual === expected;
  console.log(`${pass ? 'PASS' : 'FAIL'} ${JSON.stringify(input)} -> ${show(actual)}${pass ? '' : ` (expected ${show(expected)})`}`);
  if (!pass) failures++;
}
for (const value of REJECTED) {
  let threw = false;
  try {
    formatAmount({ value, currency: 'CHF', locale: 'de-CH' });
  } catch (e) {
    threw = e instanceof TypeError;
  }
  console.log(`${threw ? 'PASS' : 'FAIL'} rejects ${JSON.stringify(value)}`);
  if (!threw) failures++;
}
if (failures > 0) {
  console.error(`Amount format check: ${failures} case(s) fail.`);
  process.exitCode = 1;
} else {
  console.log(`Amount format check passed: ${CASES.length + REJECTED.length} cases.`);
}

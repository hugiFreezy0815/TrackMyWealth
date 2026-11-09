import type { AmountOptions } from './amount';

// The normative strings of #378 (docs/ux/check-amount-format.mjs), shared by the Node-Intl test
// and the Hermes polyfill test.
const MINUS = '−';
const NBSP = ' ';

type Case = [value: string | null, currency: string, locale: string, AmountOptions, string];

export const AMOUNT_CASES: Case[] = [
  ['-1234.5', 'CHF', 'de-CH', {}, `${MINUS}CHF${NBSP}1'234.50`],
  ['-1234.5', 'EUR', 'de-DE', {}, `${MINUS}EUR${NBSP}1'234.50`],
  ['-1234.5', 'CHF', 'en', {}, `${MINUS}CHF${NBSP}1'234.50`],
  ['478160.56', 'CHF', 'de-CH', {}, `CHF${NBSP}478'160.56`],
  ['6842.1', 'CHF', 'de-CH', { signRole: 'change' }, `+CHF${NBSP}6'842.10`],
  ['1234.565', 'CHF', 'de-CH', {}, `CHF${NBSP}1'234.57`],
  ['-1234.565', 'EUR', 'de-DE', {}, `${MINUS}EUR${NBSP}1'234.57`],
  ['1234.564', 'CHF', 'de-CH', {}, `CHF${NBSP}1'234.56`],
  ['-1234.5', 'JPY', 'en', {}, `${MINUS}JPY${NBSP}1'235`],
  ['-0.004', 'CHF', 'de-CH', {}, `CHF${NBSP}0.00`],
  ['0', 'EUR', 'de-DE', { signRole: 'change' }, `EUR${NBSP}0.00`],
  ['12345678901234567.89', 'CHF', 'de-CH', {}, `CHF${NBSP}12'345'678'901'234'567.89`],
  ['12345678901234.5678', 'CHF', 'de-CH', {}, `CHF${NBSP}12'345'678'901'234.57`],
  // Commercial rounding with carry, other currency scales, beyond double precision (#534 review).
  ['1.005', 'CHF', 'en', {}, `CHF${NBSP}1.01`],
  ['999.995', 'CHF', 'de-CH', {}, `CHF${NBSP}1'000.00`],
  ['-0.995', 'EUR', 'de-DE', {}, `${MINUS}EUR${NBSP}1.00`],
  ['999.5', 'JPY', 'en', {}, `JPY${NBSP}1'000`],
  ['0.0005', 'BHD', 'en', {}, `BHD${NBSP}0.001`],
  ['90071992547409.995', 'CHF', 'en', {}, `CHF${NBSP}90'071'992'547'410.00`],
  ['-1234.5', 'EUR', 'de', {}, `${MINUS}EUR${NBSP}1'234.50`],
  ['-1234.5', 'CHF', 'de-CH', { masked: true }, '••••'],
  [null, 'CHF', 'de-CH', {}, '–'],
  ['12.30', 'CHF', 'de-CH', { valueKnown: false }, '–'],
  ['1200', 'CHF', 'de-CH', { approx: true }, `≈CHF${NBSP}1'200.00`],
  // PO 2026-11-09: the Swiss grouping in every UI language, millions included.
  ['1111111', 'CHF', 'en', {}, `CHF${NBSP}1'111'111.00`],
  ['1122', 'EUR', 'de', {}, `EUR${NBSP}1'122.00`],
  ['1122', 'CHF', 'de-DE', {}, `CHF${NBSP}1'122.00`],
];

export const REJECTED_AMOUNTS: unknown[] = [1234.5, '1e3', '1,5', ' 1', '1.', '+1', ''];

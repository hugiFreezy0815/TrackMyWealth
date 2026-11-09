import type { AmountOptions } from './amount';

// The normative strings of #378 (docs/ux/check-amount-format.mjs), shared by the Node-Intl test
// and the Hermes polyfill test.
const MINUS = '−';
const NBSP = ' ';

type Case = [value: string | null, currency: string, locale: string, AmountOptions, string];

export const AMOUNT_CASES: Case[] = [
  ['-1234.5', 'CHF', 'de-CH', {}, `${MINUS}CHF${NBSP}1'234.50`],
  ['-1234.5', 'EUR', 'de-DE', {}, `${MINUS}EUR${NBSP}1.234,50`],
  ['-1234.5', 'CHF', 'en', {}, `${MINUS}CHF${NBSP}1,234.50`],
  ['478160.56', 'CHF', 'de-CH', {}, `CHF${NBSP}478'160.56`],
  ['6842.1', 'CHF', 'de-CH', { signRole: 'change' }, `+CHF${NBSP}6'842.10`],
  ['1234.565', 'CHF', 'de-CH', {}, `CHF${NBSP}1'234.57`],
  ['-1234.565', 'EUR', 'de-DE', {}, `${MINUS}EUR${NBSP}1.234,57`],
  ['1234.564', 'CHF', 'de-CH', {}, `CHF${NBSP}1'234.56`],
  ['-1234.5', 'JPY', 'en', {}, `${MINUS}JPY${NBSP}1,235`],
  ['-0.004', 'CHF', 'de-CH', {}, `CHF${NBSP}0.00`],
  ['0', 'EUR', 'de-DE', { signRole: 'change' }, `EUR${NBSP}0,00`],
  ['12345678901234567.89', 'CHF', 'de-CH', {}, `CHF${NBSP}12'345'678'901'234'567.89`],
  ['12345678901234.5678', 'CHF', 'de-CH', {}, `CHF${NBSP}12'345'678'901'234.57`],
  ['-1234.5', 'CHF', 'de-CH', { masked: true }, '••••'],
  [null, 'CHF', 'de-CH', {}, '–'],
  ['12.30', 'CHF', 'de-CH', { valueKnown: false }, '–'],
  ['1200', 'CHF', 'de-CH', { approx: true }, `≈CHF${NBSP}1'200.00`],
];

export const REJECTED_AMOUNTS: unknown[] = [1234.5, '1e3', '1,5', ' 1', '1.', '+1', ''];

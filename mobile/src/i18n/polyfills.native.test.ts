// ADR 0008: the Hermes Intl polyfills load under the Jest native preset, and amounts and plural
// messages format through them exactly as on a device. jest-expo resolves './polyfills' to
// polyfills.native.ts (default platform ios).
import './polyfills';

import { createIntl, createIntlCache } from 'react-intl';

import type { DecimalString } from '@/api/decimal';
import { formatAmount } from '@/format/amount';
import { AMOUNT_CASES } from '@/format/amount.cases';
import de from './de.json';
import en from './en.json';

type FormatJsNumberFormat = { __addLocaleData?: unknown };

describe('Hermes Intl polyfills', () => {
  it('replace NumberFormat with the FormatJS implementation', () => {
    expect(typeof (Intl.NumberFormat as unknown as FormatJsNumberFormat).__addLocaleData).toBe(
      'function',
    );
  });

  it.each(AMOUNT_CASES)(
    'format %s %s %s %j exactly',
    (value, currency, locale, options, expected) => {
      expect(formatAmount(value as DecimalString | null, currency, locale, options)).toBe(expected);
    },
  );

  it('render the German plural forms', () => {
    const intl = createIntl({ locale: 'de-CH', messages: { ...en, ...de } }, createIntlCache());
    expect(intl.formatMessage({ id: 'import.rows' }, { count: 1 })).toBe('1 Buchung');
    expect(intl.formatMessage({ id: 'import.rows' }, { count: 3 })).toBe('3 Buchungen');
  });
});

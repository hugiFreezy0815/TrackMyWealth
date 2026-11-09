import { createIntl, createIntlCache } from 'react-intl';

import de from '@/i18n/de.json';
import en from '@/i18n/en.json';
import type { DecimalString } from '@/api/decimal';
import { a11yAmount, formatAmount } from './amount';
import { AMOUNT_CASES, REJECTED_AMOUNTS } from './amount.cases';

const intl = (locale: string, messages: Record<string, string>) =>
  createIntl({ locale, messages }, createIntlCache());

describe('formatAmount (#378 contract)', () => {
  it.each(AMOUNT_CASES)('%s %s %s %j', (value, currency, locale, options, expected) => {
    expect(formatAmount(value as DecimalString | null, currency, locale, options)).toBe(expected);
  });

  it.each(REJECTED_AMOUNTS)('rejects %j', (value) => {
    expect(() => formatAmount(value as DecimalString, 'CHF', 'en')).toThrow(TypeError);
  });

  it('applies the de-CH group override for any spelling of the locale', () => {
    expect(formatAmount('1234' as DecimalString, 'CHF', 'de-ch')).toBe("CHF 1'234.00");
  });
});

describe('a11yAmount', () => {
  const deCH = intl('de-CH', { ...en, ...de });
  const enIntl = intl('en', en);

  it('spells sign and currency from the catalogue, without group separators', () => {
    expect(a11yAmount('-1234.5' as DecimalString, 'CHF', deCH)).toBe(
      'minus 1234.50 Schweizer Franken',
    );
    expect(a11yAmount('6842.1' as DecimalString, 'EUR', enIntl, { signRole: 'change' })).toBe(
      'plus 6842.10 euros',
    );
    expect(a11yAmount('1200' as DecimalString, 'USD', enIntl, { approx: true })).toBe(
      'approximately 1200.00 US dollars',
    );
  });

  it("keeps the reader's decimal separator while the display uses the Swiss format", () => {
    const deIntl = intl('de', { ...en, ...de });
    expect(formatAmount('1111111.5' as DecimalString, 'EUR', 'de')).toBe(`EUR\u00a01'111'111.50`);
    expect(a11yAmount('1111111.5' as DecimalString, 'EUR', deIntl)).toBe('1111111,50 Euro');
  });

  it('reads an unnamed currency as its code and a zero without sign', () => {
    expect(a11yAmount('-0.004' as DecimalString, 'GBP', enIntl)).toBe('0.00 GBP');
  });

  it('never reads digits of a hidden or unknown value', () => {
    expect(a11yAmount('1234' as DecimalString, 'CHF', deCH, { masked: true })).toBe('verborgen');
    expect(a11yAmount(null, 'CHF', enIntl)).toBe('Value unknown');
  });
});

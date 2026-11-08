import { a11yAmount, formatAmount } from './amount';

describe('exact DecimalString display', () => {
  it.each([
    ['-1234.5', 'CHF', 'de-CH', "−CHF 1'234.50"],
    ['-1234.5', 'EUR', 'de-DE', '−EUR 1.234,50'],
    ['-1234.5', 'CHF', 'en', '−CHF 1,234.50'],
    ['1234.565', 'CHF', 'de-CH', "CHF 1'234.57"],
    ['-0.004', 'CHF', 'de-CH', 'CHF 0.00'],
    ['12345678901234567.89', 'CHF', 'de-CH', "CHF 12'345'678'901'234'567.89"],
  ])('%s %s %s', (value, currency, locale, expected) => {
    expect(formatAmount(value, currency, locale)).toBe(expected);
  });
  it('shows changes, unknowns and hidden amounts', () => {
    expect(formatAmount('6842.1', 'CHF', 'de-CH', { signRole: 'change' })).toBe("+CHF 6'842.10");
    expect(formatAmount(null, 'CHF', 'en')).toBe('–');
    expect(formatAmount('1234', 'CHF', 'en', { masked: true })).toBe('••••');
    expect(a11yAmount('1234', 'CHF', 'en', { masked: true })).toBe('hidden');
  });
  it.each(['1e3', '1,5', ' 1', '1.', '+1', ''] )('rejects non-plain decimals', (value) => {
    expect(() => formatAmount(value, 'CHF', 'en')).toThrow(TypeError);
  });
});

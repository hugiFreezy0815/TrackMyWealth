import { render, screen } from '@testing-library/react-native';

import { Amount, accessibleAmount, displayAmount } from './amount';

jest.mock('@/global.css', () => ({}));
jest.mock('@/hooks/use-theme', () => ({ useTheme: () => ({ textPrimary: '#0B1C3F' }) }));

describe('UX-03 normative DecimalString amount formatting', () => {
  it.each([
    [{ value: '-1234.5', currency: 'CHF', locale: 'de-CH' }, "−CHF\u00a01'234.50"],
    [{ value: '-1234.5', currency: 'EUR', locale: 'de-DE' }, '−EUR\u00a01.234,50'],
    [{ value: '-1234.5', currency: 'CHF', locale: 'en' }, '−CHF\u00a01,234.50'],
    [{ value: '478160.56', currency: 'CHF', locale: 'de-CH' }, "CHF\u00a0478'160.56"],
    [{ value: '6842.1', currency: 'CHF', locale: 'de-CH', signRole: 'change' }, "+CHF\u00a06'842.10"],
    [{ value: '1234.565', currency: 'CHF', locale: 'de-CH' }, "CHF\u00a01'234.57"],
    [{ value: '-1234.565', currency: 'EUR', locale: 'de-DE' }, '−EUR\u00a01.234,57'],
    [{ value: '1234.564', currency: 'CHF', locale: 'de-CH' }, "CHF\u00a01'234.56"],
    [{ value: '-1234.5', currency: 'JPY', locale: 'en' }, '−JPY\u00a01,235'],
    [{ value: '-0.004', currency: 'CHF', locale: 'de-CH' }, 'CHF\u00a00.00'],
    [{ value: '0', currency: 'EUR', locale: 'de-DE', signRole: 'change' }, 'EUR\u00a00,00'],
    [{ value: '12345678901234567.89', currency: 'CHF', locale: 'de-CH' }, "CHF\u00a012'345'678'901'234'567.89"],
    [{ value: '-1234.5', currency: 'CHF', locale: 'de-CH', masked: true }, '••••'],
    [{ value: null, currency: 'CHF', locale: 'de-CH' }, '–'],
    [{ value: '12.30', currency: 'CHF', locale: 'de-CH', valueKnown: false }, '–'],
  ] as const)('%j gives %s', async (options, expected) => {
    expect(displayAmount(options)).toBe(expected);
  });
  it.each([1234.5, '1e3', '1,5', ' 1', '1.', '+1', ''])('rejects invalid decimal %j', async (value) => {
    expect(() => displayAmount({ value: value as string, currency: 'CHF' })).toThrow(TypeError);
  });
  it('never exposes a hidden amount through accessibility text', async () => {
    await render(<Amount value="1234.56" currency="CHF" locale="en" masked />);
    expect(screen.getByText('••••').props.accessibilityLabel).toBe('Amount hidden');
    expect(JSON.stringify(screen.toJSON())).not.toContain('1234');
  });
  it('reads unknown values as unknown rather than zero', async () => {
    expect(accessibleAmount({ value: null, currency: 'EUR', locale: 'de-DE' })).toBe('Wert unbekannt');
  });
});

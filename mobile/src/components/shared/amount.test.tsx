import { render, screen } from '@testing-library/react-native';

import { Amount, accessibleAmount, displayAmount } from './amount';

jest.mock('@/global.css', () => ({}));
jest.mock('@/hooks/use-theme', () => ({ useTheme: () => ({ textPrimary: '#0B1C3F' }) }));

describe('UX-03 normative DecimalString amount formatting', () => {
  it.each([
    [{ value: '-1234.5', currency: 'CHF', locale: 'de-CH' }, "−CHF 1'234.50"],
    [{ value: '-1234.5', currency: 'EUR', locale: 'de-DE' }, '−EUR 1.234,50'],
    [{ value: '-1234.5', currency: 'CHF', locale: 'en' }, '−CHF 1,234.50'],
    [{ value: '478160.56', currency: 'CHF', locale: 'de-CH' }, "CHF 478'160.56"],
    [{ value: '6842.1', currency: 'CHF', locale: 'de-CH', signRole: 'change' }, "+CHF 6'842.10"],
    [{ value: '1234.565', currency: 'CHF', locale: 'de-CH' }, "CHF 1'234.57"],
    [{ value: '-1234.565', currency: 'EUR', locale: 'de-DE' }, '−EUR 1.234,57'],
    [{ value: '1234.564', currency: 'CHF', locale: 'de-CH' }, "CHF 1'234.56"],
    [{ value: '1.005', currency: 'CHF', locale: 'en' }, 'CHF 1.01'],
    [{ value: '999.995', currency: 'CHF', locale: 'de-CH' }, "CHF 1'000.00"],
    [{ value: '-0.995', currency: 'EUR', locale: 'de-DE' }, '−EUR 1,00'],
    [{ value: '-1234.5', currency: 'JPY', locale: 'en' }, '−JPY 1,235'],
    [{ value: '999.5', currency: 'JPY', locale: 'en' }, 'JPY 1,000'],
    [{ value: '0.0005', currency: 'BHD', locale: 'en' }, 'BHD 0.001'],
    [{ value: '0007.1', currency: 'CHF', locale: 'en' }, 'CHF 7.10'],
    [{ value: '-0.004', currency: 'CHF', locale: 'de-CH' }, 'CHF 0.00'],
    [{ value: '0', currency: 'EUR', locale: 'de-DE', signRole: 'change' }, 'EUR 0,00'],
    [
      { value: '12345678901234567.89', currency: 'CHF', locale: 'de-CH' },
      "CHF 12'345'678'901'234'567.89",
    ],
    [{ value: '90071992547409.995', currency: 'CHF', locale: 'en' }, 'CHF 90,071,992,547,410.00'],
    [{ value: '1234.5', currency: 'CHF', locale: 'en', approx: true }, '≈CHF 1,234.50'],
    [{ value: '-1234.5', currency: 'CHF', locale: 'de-CH', masked: true }, '••••'],
    [{ value: null, currency: 'CHF', locale: 'de-CH' }, '–'],
    [{ value: '12.30', currency: 'CHF', locale: 'de-CH', valueKnown: false }, '–'],
  ] as const)('%j gives %s', async (options, expected) => {
    expect(displayAmount(options)).toBe(expected);
  });

  it('rounds the digits without converting them to a JS number', async () => {
    const toNumber = jest.spyOn(Intl.NumberFormat.prototype, 'formatToParts');
    displayAmount({ value: '12345678901234567.89', currency: 'CHF', locale: 'en' });
    expect(toNumber).not.toHaveBeenCalled();
    toNumber.mockRestore();
  });

  it.each([1234.5, '1e3', '1,5', ' 1', '1.', '+1', ''])(
    'rejects invalid decimal %j',
    async (value) => {
      expect(() => displayAmount({ value: value as string, currency: 'CHF' })).toThrow(TypeError);
    },
  );

  it.each(['USDT', 'chf', '', 'C1F'])(
    'rejects the malformed currency code %j',
    async (currency) => {
      expect(() => displayAmount({ value: '1', currency })).toThrow(TypeError);
    },
  );

  it('never exposes a hidden amount through accessibility text', async () => {
    await render(<Amount value="1234.56" currency="CHF" locale="en" masked />);
    expect(screen.getByText('••••').props.accessibilityLabel).toBe('Amount hidden');
    expect(JSON.stringify(screen.toJSON())).not.toContain('1234');
  });

  it('keeps the hidden label even when the caller passes its own label', async () => {
    await render(
      <Amount value="1234.56" currency="CHF" locale="de-CH" masked accessibilityLabel="1234.56" />,
    );
    expect(screen.getByText('••••').props.accessibilityLabel).toBe('Betrag verborgen');
  });

  it.each([[{ value: '12.5', currency: 'USDT' }], [{ value: '1,5', currency: 'CHF' }]])(
    'renders unformattable response data %j as unknown instead of failing',
    async (props) => {
      const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
      await render(<Amount {...props} locale="en" />);
      expect(screen.getByText('–').props.accessibilityLabel).toBe('Value unknown');
      expect(warn).toHaveBeenCalled();
      warn.mockRestore();
    },
  );

  it('reads unknown values as unknown rather than zero', async () => {
    expect(accessibleAmount({ value: null, currency: 'EUR', locale: 'de-DE' })).toBe(
      'Wert unbekannt',
    );
  });

  it.each([
    [{ value: '-12.5', locale: 'en' }, /^minus 12\.50 /],
    [{ value: '-12.5', locale: 'de-DE' }, /^minus 12,50 /],
    [{ value: '12.5', locale: 'en', signRole: 'change' }, /^plus 12\.50 /],
    [{ value: '12.5', locale: 'en' }, /^12\.50 /],
    [{ value: '-12.5', locale: 'en', approx: true }, /^approximately minus 12\.50 /],
    [{ value: '12.5', locale: 'de-CH', approx: true }, /^ungefähr 12\.50 /],
  ] as const)('announces %j from the catalogue', async (options, expected) => {
    expect(accessibleAmount({ currency: 'CHF', ...options })).toMatch(expected);
  });
});

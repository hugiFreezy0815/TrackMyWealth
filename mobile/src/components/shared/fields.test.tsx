import { fireEvent, render, screen } from '@testing-library/react-native';

import { AmountField, CurrencyPicker, isValidIsoDate, parseAmountInput } from './fields';

jest.mock('@/global.css', () => ({}));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => ({
    textPrimary: '#0B1C3F',
    textSecondary: '#53627A',
    textTertiary: '#5E6D83',
    bgSurface: '#FFFFFF',
    brandSubtle: '#E0F5F1',
    borderInteractive: '#64758A',
    statusLoss: '#C42F37',
  }),
}));

describe('locale-aware decimal input', () => {
  it.each([
    ["1'234.5", 'de-CH', '1234.5'],
    ['1.234,5', 'de-DE', '1234.5'],
    ['1,234.50', 'en', '1234.50'],
    ['0001.50', 'en', '1.50'],
    ['12345678901234567.89', 'en', '12345678901234567.89'],
    ['-0,01', 'de-DE', '-0.01'],
  ] as const)('parses %s in %s into %s without floats', async (raw, locale, expected) => {
    expect(parseAmountInput(raw, locale)).toBe(expected);
  });
  it.each(['1e3', '1+2', '1,2,3', '', '10.'])('rejects invalid en input %s', async (input) => {
    expect(parseAmountInput(input, 'en')).toBeNull();
  });
  it('emits a plain decimal string from a field', async () => {
    const onChangeValue = jest.fn();
    await render(
      <AmountField
        label="Opening balance"
        value="1'234.5"
        currency="CHF"
        locale="de-CH"
        onChangeValue={onChangeValue}
      />,
    );
    await fireEvent.changeText(screen.getByLabelText('Opening balance'), "1'234.5");
    expect(onChangeValue).toHaveBeenCalledWith('1234.5');
  });
  it('offers accessible selection and a usable touch target', async () => {
    const onChange = jest.fn();
    await render(
      <CurrencyPicker
        label="Currency"
        options={[
          { value: 'CHF', label: 'CHF' },
          { value: 'EUR', label: 'EUR' },
        ]}
        value="CHF"
        onChange={onChange}
      />,
    );
    await fireEvent.press(screen.getByRole('radio', { name: 'EUR' }));
    expect(onChange).toHaveBeenCalledWith('EUR');
  });
  it.each([
    ['2024-02-29', true],
    ['2025-02-29', false],
    ['2025-13-01', false],
    ['2025-04-31', false],
    ['2025-04-30', true],
    ['2025-1-01', false],
  ])('validates ISO date %s', async (value, expected) => {
    expect(isValidIsoDate(value)).toBe(expected);
  });
});

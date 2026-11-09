import { fireEvent, render, screen } from '@testing-library/react-native';

import {
  AmountField,
  CurrencyPicker,
  DateField,
  amountInputText,
  isValidIsoDate,
  parseAmountInput,
} from './fields';

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
    ['1’234.5', 'de-CH', '1234.5'],
    ['1.234,5', 'de-DE', '1234.5'],
    ['1,234.50', 'en', '1234.50'],
    ['0001.50', 'en', '1.50'],
    ['12345678901234567.89', 'en', '12345678901234567.89'],
    ['-0,01', 'de-DE', '-0.01'],
    ['−12.5', 'de-CH', '-12.5'],
  ] as const)('parses %s in %s into %s without floats', async (raw, locale, expected) => {
    expect(parseAmountInput(raw, locale)).toBe(expected);
  });
  it.each(['1e3', '1+2', '1,2,3', '', '10.', '1,23.4'])(
    'rejects invalid en input %s',
    async (input) => {
      expect(parseAmountInput(input, 'en')).toBeNull();
    },
  );
  it.each([
    ['1234.5', 'de-DE', '1234,5'],
    ['1234.5', 'de-CH', '1234.5'],
    [null, 'en', ''],
  ] as const)('edits %s in %s as %j', async (value, locale, expected) => {
    expect(amountInputText(value, locale)).toBe(expected);
  });

  it.each([
    ["1'234.5", 'de-CH'],
    ['1.234,5', 'de-DE'],
  ] as const)('emits "1234.5" from the field input %s in %s', async (input, locale) => {
    const onChangeValue = jest.fn();
    await render(
      <AmountField
        label="Opening balance"
        value={null}
        currency="CHF"
        locale={locale}
        onChangeValue={onChangeValue}
      />,
    );
    await fireEvent.changeText(screen.getByLabelText('Opening balance'), input);
    expect(onChangeValue).toHaveBeenLastCalledWith('1234.5');
  });

  it('keeps the typed text while it stands for the value, and shows a new value from the parent', async () => {
    const props = {
      label: 'Amount',
      currency: 'CHF',
      locale: 'de-CH',
      onChangeValue: jest.fn(),
    } as const;
    const view = await render(<AmountField {...props} value="10" />);
    const input = () => screen.getByLabelText('Amount');
    await fireEvent.changeText(input(), "1'234.5");
    // The parent stores what the field emitted: the member's grouping stays visible.
    await view.rerender(<AmountField {...props} value="1234.5" />);
    expect(input().props.value).toBe("1'234.5");
    // A reset or a conflict reload replaces the draft.
    await view.rerender(<AmountField {...props} value="99.95" />);
    expect(input().props.value).toBe('99.95');
  });

  it('shows the invalid-amount error after blur and keeps the text', async () => {
    const onChangeValue = jest.fn();
    await render(
      <AmountField
        label="Amount"
        value={null}
        currency="EUR"
        locale="en"
        onChangeValue={onChangeValue}
      />,
    );
    await fireEvent.changeText(screen.getByLabelText('Amount'), '1,23.4');
    expect(onChangeValue).toHaveBeenLastCalledWith(null);
    expect(screen.queryByRole('alert')).toBeNull();
    await fireEvent(screen.getByLabelText('Amount'), 'blur');
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a valid amount.');
    expect(screen.getByLabelText('Amount').props.value).toBe('1,23.4');
  });

  it('offers accessible single selection and a usable touch target', async () => {
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
    expect(screen.getAllByLabelText('Currency')[0].props.accessibilityRole).toBe('radiogroup');
    expect(screen.getByRole('radio', { name: 'CHF' }).props.accessibilityState.checked).toBe(true);
    expect(screen.getByRole('radio', { name: 'EUR' }).props.accessibilityState.checked).toBe(false);
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

describe('DateField', () => {
  it('emits a valid ISO date and null for an invalid one', async () => {
    const onChange = jest.fn();
    await render(<DateField label="Date" value={null} onChange={onChange} locale="en" />);
    await fireEvent.changeText(screen.getByLabelText('Date'), '2026-11-08');
    expect(onChange).toHaveBeenLastCalledWith('2026-11-08');
    await fireEvent.changeText(screen.getByLabelText('Date'), '2026-13-08');
    expect(onChange).toHaveBeenLastCalledWith(null);
  });

  it('keeps invalid text and its error after blur', async () => {
    const view = await render(
      <DateField label="Date" value="2026-11-08" onChange={jest.fn()} locale="en" />,
    );
    await fireEvent.changeText(screen.getByLabelText('Date'), '2026-13-08');
    // The parent stores the null the field emitted.
    await view.rerender(<DateField label="Date" value={null} onChange={jest.fn()} locale="en" />);
    await fireEvent(screen.getByLabelText('Date'), 'blur');
    expect(screen.getByLabelText('Date').props.value).toBe('2026-13-08');
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a valid date (YYYY-MM-DD).');
  });

  it('shows a new date from the parent and a localized placeholder', async () => {
    const view = await render(
      <DateField label="Datum" value="2026-11-08" onChange={jest.fn()} locale="de-CH" />,
    );
    expect(screen.getByLabelText('Datum').props.placeholder).toBe('JJJJ-MM-TT');
    await fireEvent.changeText(screen.getByLabelText('Datum'), '2026-11-0');
    await view.rerender(
      <DateField label="Datum" value="2026-01-31" onChange={jest.fn()} locale="de-CH" />,
    );
    expect(screen.getByLabelText('Datum').props.value).toBe('2026-01-31');
  });
});

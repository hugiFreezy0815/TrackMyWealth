import { fireEvent, screen } from '@testing-library/react-native';

import type { AppLocale } from '@/i18n';
import { renderWithIntl } from '@/i18n/test-utils';
import {
  AmountField,
  CurrencyPicker,
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
    ['1.234,5', 'de', '1234.5'],
    ['1,234.50', 'en', '1234.50'],
    ['0001.50', 'en', '1.50'],
    ['12345678901234567.89', 'en', '12345678901234567.89'],
    ['-0,01', 'de-DE', '-0.01'],
    ['−12.5', 'de-CH', '-12.5'],
  ])('parses %s in %s into %s without floats', async (raw, locale, expected) => {
    expect(parseAmountInput(raw, locale)).toBe(expected);
  });
  it.each(['1e3', '1+2', '1,2,3', '', '10.', '1,23.4'])(
    'rejects invalid en input %s',
    async (input) => {
      expect(parseAmountInput(input, 'en')).toBeNull();
    },
  );
  // #534: the decimal-pad key follows the device region, not the app language.
  it.each([
    ['12.5', 'de', '.', '12.5'], // Swiss phone, app in German: "." is the only decimal key
    ['12,5', 'de', '.', '12.5'],
    ['1.234,5', 'de', '.', '1234.5'],
    ['1.234.567', 'de', ',', '1234567'],
    ['1.250', 'de', ',', '1250'], // German phone: "." only groups
    ['1.250', 'en', ',', '1.250'], // "." is never a group in English
    ['12,5', 'en', ',', '12.5'], // English app on a German phone: "," is the decimal key
    ['1,234,567', 'en', ',', '1234567'],
    ['1,234.5', 'en', ',', '1234.5'],
    ["1'234,5", 'de-CH', ',', '1234.5'],
    ['1250.75', 'de-CH', ',', '1250.75'],
  ] as const)(
    'parses %s in %s on a device with decimal "%s" into %s',
    (raw, locale, device, expected) => {
      expect(parseAmountInput(raw, locale, device)).toBe(expected);
    },
  );

  it.each([
    ['1.250', 'de', '.'], // 1.25 on the keypad or 1250 grouped: never guess
    ['1,250', 'en', ','],
    ['12,500', 'en', ','],
    ['1,250', 'de-CH', '.'], // "," is neither the decimal key nor a Swiss group here
    ['1.234,5,6', 'de', ','],
    ["1'234.567,5", 'de-CH', '.'],
    ['.5', 'en', '.'],
  ] as const)('rejects ambiguous or invalid %s in %s on a "%s" device', (raw, locale, device) => {
    expect(parseAmountInput(raw, locale, device)).toBeNull();
  });

  it('flags neither an empty nor a valid field as invalid', async () => {
    const onChangeValue = jest.fn();
    await renderWithIntl(
      <AmountField label="Amount" value="5" currency="CHF" onChangeValue={onChangeValue} />,
    );
    await fireEvent.changeText(screen.getByLabelText('Amount'), '  ');
    expect(onChangeValue).toHaveBeenLastCalledWith(null, { invalid: false });
  });

  it.each([
    ['1234.5', 'de-DE', '1234,5'],
    ['1234.5', 'de', '1234,5'],
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
    await renderWithIntl(
      <AmountField label="Betrag" value={null} currency="CHF" onChangeValue={onChangeValue} />,
      locale as AppLocale,
    );
    await fireEvent.changeText(screen.getByLabelText('Betrag'), input);
    expect(onChangeValue).toHaveBeenLastCalledWith('1234.5', { invalid: false });
  });

  it('keeps the typed text while it stands for the value, and shows a new value from the parent', async () => {
    const props = { label: 'Amount', currency: 'CHF', onChangeValue: jest.fn() };
    const view = await renderWithIntl(<AmountField {...props} value="10" />, 'de-CH');
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
    await renderWithIntl(
      <AmountField label="Amount" value={null} currency="EUR" onChangeValue={onChangeValue} />,
    );
    await fireEvent.changeText(screen.getByLabelText('Amount'), '1,23.4');
    // Invalid text emits null like an empty field, but flagged so the form blocks saving.
    expect(onChangeValue).toHaveBeenLastCalledWith(null, { invalid: true });
    expect(screen.queryByRole('alert')).toBeNull();
    await fireEvent(screen.getByLabelText('Amount'), 'blur');
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a valid amount.');
    expect(screen.getByLabelText('Amount').props.value).toBe('1,23.4');
  });

  it('offers accessible single selection and a usable touch target', async () => {
    const onChange = jest.fn();
    await renderWithIntl(
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
    expect(screen.getByRole('radio', { name: 'CHF' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'EUR' })).not.toBeChecked();
    await fireEvent.press(screen.getByRole('radio', { name: 'EUR' }));
    expect(onChange).toHaveBeenCalledWith('EUR');
  });

  it('disables every currency while the picker is disabled', async () => {
    const onChange = jest.fn();
    await renderWithIntl(
      <CurrencyPicker
        label="Currency"
        options={[{ value: 'CHF', label: 'CHF' }]}
        value={null}
        onChange={onChange}
        disabled
      />,
    );
    const radio = screen.getByRole('radio', { name: 'CHF' });
    expect(radio).not.toBeChecked();
    expect(radio).toBeDisabled();
    await fireEvent.press(radio);
    expect(onChange).not.toHaveBeenCalled();
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

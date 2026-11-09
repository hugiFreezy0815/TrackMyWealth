import { fireEvent, screen } from '@testing-library/react-native';
import { Platform } from 'react-native';
import { DateTimePickerAndroid } from '@react-native-community/datetimepicker';

import { renderWithIntl } from '@/i18n/test-utils';
import { DateField, isoToLocalDate, localDateToIso } from './date-field';

jest.mock('@/global.css', () => ({}));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => ({
    textPrimary: '#0B1C3F',
    textSecondary: '#53627A',
    textTertiary: '#5E6D83',
    textOnBrand: '#FFFFFF',
    brandPrimary: '#00857A',
    brandLink: '#006D64',
    bgSurface: '#FFFFFF',
    bgOverlay: '#0B1C3F99',
    borderInteractive: '#64758A',
    statusLoss: '#C42F37',
  }),
}));
jest.mock('@react-native-community/datetimepicker', () => ({
  __esModule: true,
  default: 'DateTimePicker',
  DateTimePickerAndroid: { open: jest.fn() },
}));

type Node = { type: string; props: Record<string, unknown>; children: (Node | string)[] | null };
function findHost(node: unknown, type: string): Node | undefined {
  if (!node || typeof node !== 'object') return undefined;
  if (Array.isArray(node)) {
    for (const child of node) {
      const found = findHost(child, type);
      if (found) return found;
    }
    return undefined;
  }
  const host = node as Node;
  return host.type === type ? host : findHost(host.children, type);
}

describe('DateField (native date picker)', () => {
  const originalOS = Platform.OS;
  afterEach(() => {
    Platform.OS = originalOS;
    jest.resetAllMocks();
  });

  it('converts between a calendar date and the local Date the picker uses', () => {
    expect(isoToLocalDate('2026-03-29').getDate()).toBe(29);
    expect(localDateToIso(new Date(2026, 2, 29, 23, 59))).toBe('2026-03-29');
  });

  it.each([
    ['en', 'Booking date, 1 Oct 2026'],
    ['de-CH', 'Booking date, 01.10.2026'],
  ] as const)('shows and announces the chosen date in %s', async (locale, label) => {
    await renderWithIntl(
      <DateField label="Booking date" value="2026-10-01" onChange={jest.fn()} />,
      locale,
    );
    expect(screen.getByRole('button', { name: label })).toBeTruthy();
  });

  it('prompts to choose a date when none is set', async () => {
    await renderWithIntl(<DateField label="Datum" value={null} onChange={jest.fn()} />, 'de-DE');
    expect(screen.getByRole('button', { name: 'Datum, Datum wählen' })).toBeTruthy();
  });

  it('picks a date in the iOS inline calendar and confirms it with Done', async () => {
    Platform.OS = 'ios';
    const onChange = jest.fn();
    await renderWithIntl(
      <DateField
        label="Booking date"
        value="2026-10-01"
        onChange={onChange}
        maximumDate="2026-12-31"
        testID="booking"
      />,
    );
    await fireEvent.press(screen.getByRole('button', { name: /^Booking date, / }));
    const picker = findHost(screen.toJSON(), 'DateTimePicker');
    expect(picker?.props).toEqual(
      expect.objectContaining({ mode: 'date', display: 'inline', locale: 'en' }),
    );
    expect((picker?.props.maximumDate as Date).getDate()).toBe(31);
    await fireEvent(
      screen.getByTestId('booking-picker'),
      'change',
      { type: 'set' },
      new Date(2026, 10, 8),
    );
    expect(onChange).not.toHaveBeenCalled();
    await fireEvent.press(screen.getByRole('button', { name: 'Done' }));
    expect(onChange).toHaveBeenCalledWith('2026-11-08');
  });

  it('closes the iOS sheet without a change', async () => {
    Platform.OS = 'ios';
    const onChange = jest.fn();
    await renderWithIntl(<DateField label="Date" value={null} onChange={onChange} />);
    await fireEvent.press(screen.getByRole('button', { name: /^Date, / }));
    await fireEvent.press(screen.getByRole('button', { name: 'Close' }));
    expect(onChange).not.toHaveBeenCalled();
    expect(findHost(screen.toJSON(), 'DateTimePicker')).toBeUndefined();
  });

  it('opens the Android dialog and emits only a confirmed date', async () => {
    Platform.OS = 'android';
    const onChange = jest.fn();
    await renderWithIntl(<DateField label="Date" value="2026-10-01" onChange={onChange} />);
    await fireEvent.press(screen.getByRole('button', { name: /^Date, / }));
    const open = jest.mocked(DateTimePickerAndroid.open);
    expect(open).toHaveBeenCalledTimes(1);
    const options = open.mock.calls[0][0];
    expect(options.mode).toBe('date');
    expect(localDateToIso(options.value)).toBe('2026-10-01');
    options.onChange?.({ type: 'dismissed', nativeEvent: { timestamp: 0, utcOffset: 0 } });
    expect(onChange).not.toHaveBeenCalled();
    options.onChange?.(
      { type: 'set', nativeEvent: { timestamp: 0, utcOffset: 0 } },
      new Date(2026, 0, 31),
    );
    expect(onChange).toHaveBeenCalledWith('2026-01-31');
  });

  it('shows an error from the form under the field', async () => {
    await renderWithIntl(
      <DateField label="Date" value={null} onChange={jest.fn()} error="Required" />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Required');
  });
});

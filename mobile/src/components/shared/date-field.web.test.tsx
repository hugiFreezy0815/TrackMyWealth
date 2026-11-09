import { fireEvent, screen } from '@testing-library/react-native';

import { renderWithIntl } from '@/i18n/test-utils';
import { DateField } from './date-field.web';

jest.mock('@/global.css', () => ({}));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => ({
    textPrimary: '#0B1C3F',
    textSecondary: '#53627A',
    bgSurface: '#FFFFFF',
    borderInteractive: '#64758A',
    statusLoss: '#C42F37',
  }),
}));

describe('DateField (web, browser date input)', () => {
  it('renders a labelled date input with the ISO value and limits', async () => {
    await renderWithIntl(
      <DateField
        label="Booking date"
        value="2026-10-01"
        onChange={jest.fn()}
        minimumDate="2026-01-01"
        maximumDate="2026-12-31"
      />,
      'de-CH',
    );
    const input = screen.getByLabelText('Booking date');
    expect(input.props).toEqual(
      expect.objectContaining({
        type: 'date',
        value: '2026-10-01',
        min: '2026-01-01',
        max: '2026-12-31',
        lang: 'de-CH',
      }),
    );
    // A unitless CSS line-height would multiply the font size.
    expect((input.props.style as { lineHeight: string }).lineHeight).toBe('24px');
  });

  it('emits a chosen date and ignores a cleared or partial one', async () => {
    const onChange = jest.fn();
    await renderWithIntl(<DateField label="Date" value={null} onChange={onChange} />);
    const input = screen.getByLabelText('Date');
    expect(input.props.value).toBe('');
    await fireEvent(input, 'change', { target: { value: '' } });
    expect(onChange).not.toHaveBeenCalled();
    await fireEvent(input, 'change', { target: { value: '2026-11-08' } });
    expect(onChange).toHaveBeenCalledWith('2026-11-08');
  });

  it('clears an optional date with the browser control', async () => {
    const onChange = jest.fn();
    const onClear = jest.fn();
    await renderWithIntl(
      <DateField label="Date" value="2026-11-08" onChange={onChange} onClear={onClear} />,
    );
    await fireEvent(screen.getByLabelText('Date'), 'change', { target: { value: '' } });
    expect(onClear).toHaveBeenCalledTimes(1);
    expect(onChange).not.toHaveBeenCalled();
  });
});

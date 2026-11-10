import { screen } from '@testing-library/react-native';
import { StyleSheet } from 'react-native';

import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { renderWithIntl } from '@/i18n/test-utils';
import { Figure } from './figure';

jest.mock('@/global.css', () => ({}));
jest.mock('@/features/privacy/amount-visibility', () => ({
  useAmountsHidden: jest.fn(() => false),
}));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => ({ textSecondary: '#53627A', statusGain: '#007D55', statusLoss: '#C42F37' }),
}));

const colour = (text: string) =>
  (StyleSheet.flatten(screen.getAllByText(text)[0].props.style) as { color: string }).color;

beforeEach(() => jest.mocked(useAmountsHidden).mockReturnValue(false));

// The normative formats are tested in src/format/figure.test.ts.
describe('Figure (#493)', () => {
  it('shows a signed percentage in gain/loss colour and reads it aloud', async () => {
    await renderWithIntl(
      <>
        <Figure value="11.24" kind="percent" signRole="change" tone="gainLoss" />
        <Figure value="-3.75" kind="percent" signRole="change" tone="gainLoss" />
        <Figure value="0.00" kind="percent" signRole="change" tone="gainLoss" />
      </>,
    );
    expect(screen.getByText('+11.2%').props.accessibilityLabel).toBe('plus 11.2 percent');
    expect(colour('+11.2%')).toBe('#007D55');
    expect(colour('−3.8%')).toBe('#C42F37');
    // A zero gain has no sign and no gain colour.
    expect(colour('0.0%')).toBe('#53627A');
  });

  it('masks units, percentages and rates while "Hide amounts" is on, in neutral colour', async () => {
    jest.mocked(useAmountsHidden).mockReturnValue(true);
    await renderWithIntl(
      <>
        <Figure value="-3.75" kind="percent" signRole="change" tone="gainLoss" />
        <Figure value="310.5" kind="quantity" accessibilityLabel="310.5 units" />
        <Figure value="0.9362" kind="rate" />
      </>,
      'de-CH',
    );
    const masked = screen.getAllByText('••••');
    expect(masked).toHaveLength(3);
    masked.forEach((node) => expect(node.props.accessibilityLabel).toBe('verborgen'));
    expect(colour('••••')).toBe('#53627A');
    expect(JSON.stringify(screen.toJSON())).not.toMatch(/3[.,]75|310|9362/);
  });

  it('renders unformattable response data as unknown instead of failing', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    await renderWithIntl(<Figure value="1e3" kind="quantity" />);
    expect(screen.getByText('–').props.accessibilityLabel).toBe('Value unknown');
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });
});

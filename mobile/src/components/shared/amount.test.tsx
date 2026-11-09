import { screen } from '@testing-library/react-native';

import { renderWithIntl } from '@/i18n/test-utils';
import { Amount } from './amount';

jest.mock('@/global.css', () => ({}));
jest.mock('@/hooks/use-theme', () => ({ useTheme: () => ({ textPrimary: '#0B1C3F' }) }));

// The normative formats themselves are tested in src/format (Node Intl and the Hermes polyfill).
describe('Amount', () => {
  it('shows the #378 format and reads sign and currency aloud', async () => {
    await renderWithIntl(<Amount value="-1234.5" currency="CHF" />, 'de-CH');
    const amount = screen.getByText("−CHF 1'234.50");
    expect(amount.props.accessibilityLabel).toBe('minus 1234.50 Schweizer Franken');
  });

  it.each([
    ['en', 'hidden'],
    ['de-CH', 'verborgen'],
  ] as const)('never exposes a hidden amount (%s)', async (locale, label) => {
    await renderWithIntl(<Amount value="1234.56" currency="CHF" masked />, locale);
    expect(screen.getByText('••••').props.accessibilityLabel).toBe(label);
    expect(JSON.stringify(screen.toJSON())).not.toContain('1234');
  });

  it('keeps the hidden label even when the caller passes its own label', async () => {
    await renderWithIntl(
      <Amount value="1234.56" currency="CHF" masked accessibilityLabel="1234.56" />,
    );
    expect(screen.getByText('••••').props.accessibilityLabel).toBe('hidden');
  });

  it('reads an unknown value as unknown rather than zero', async () => {
    await renderWithIntl(<Amount value={null} currency="EUR" />, 'de-DE');
    expect(screen.getByText('–').props.accessibilityLabel).toBe('Wert unbekannt');
  });

  it.each([[{ value: '12.5', currency: 'USDT' }], [{ value: '1,5', currency: 'CHF' }]])(
    'renders unformattable response data %j as unknown instead of failing',
    async (props) => {
      const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
      await renderWithIntl(<Amount {...props} />);
      expect(screen.getByText('–').props.accessibilityLabel).toBe('Value unknown');
      expect(warn).toHaveBeenCalled();
      warn.mockRestore();
    },
  );
});

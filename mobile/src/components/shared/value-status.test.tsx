import { fireEvent, screen } from '@testing-library/react-native';

import type { AppLocale } from '@/i18n';
import { renderWithIntl } from '@/i18n/test-utils';
import { isValueKnown, ValueStatusBadge } from './value-status';

jest.mock('@/global.css', () => ({}));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => ({
    textPrimary: '#0B1C3F',
    textSecondary: '#53627A',
    statusWarning: '#8F5200',
    statusWarningSubtle: '#FFF3D9',
    statusInfo: '#1F5CC4',
    statusInfoSubtle: '#E6EFFF',
    bgSubtle: '#ECF1F4',
    bgSurface: '#FFFFFF',
    bgOverlay: '#0B1C3F99',
    brandLink: '#006D64',
  }),
}));

describe('ValueStatusBadge', () => {
  beforeEach(() => jest.spyOn(console, 'warn').mockImplementation(() => {}));
  afterEach(() => jest.restoreAllMocks());

  it.each(['CURRENT', null, undefined])('renders nothing for %s', async (status) => {
    const { toJSON } = await renderWithIntl(<ValueStatusBadge status={status} />);
    expect(toJSON()).toBeNull();
  });

  it.each([
    ['ESTIMATED', 'en', 'Estimated', /rests on an approximation/],
    ['STALE', 'en', 'Price from 1 Oct 2026', /older than usual/],
    ['UNRECONCILED', 'en', 'Differs from statement', /disagree/],
    ['UNKNOWN', 'en', 'Value unknown', /not available/],
    ['FUTURE_SERVER_ENUM', 'en', 'Value unknown', /not available/],
    ['toString', 'en', 'Value unknown', /not available/],
    ['ESTIMATED', 'de-CH', 'Geschätzt', /Schätzung/],
    ['STALE', 'de-CH', 'Kurs vom 01.10.2026', /älter als üblich/],
    ['UNRECONCILED', 'de-DE', 'Weicht vom Auszug ab', /stimmen nicht überein/],
    ['UNKNOWN', 'de-DE', 'Wert unbekannt', /kein verlässlicher Wert/],
    ['FUTURE_SERVER_ENUM', 'de-DE', 'Wert unbekannt', /kein verlässlicher Wert/],
  ] as const)(
    'renders %s in %s as %s with its explanation',
    async (status, locale, label, detail) => {
      await renderWithIntl(
        <ValueStatusBadge status={status} valueSourceDate="2026-10-01" />,
        locale as AppLocale,
      );
      expect(screen.getByText(label)).toBeTruthy();
      const badge = screen.getByRole('button', { name: new RegExp('^' + label + '\\. ') });
      expect(badge.props.accessibilityLabel).toMatch(detail);
    },
  );

  it('says the price is outdated when STALE comes without a date', async () => {
    await renderWithIntl(<ValueStatusBadge status="STALE" />);
    expect(screen.getByText('Price outdated')).toBeTruthy();
  });

  it.each([
    ['de-CH', 'Erklärung schliessen'],
    ['de-DE', 'Erklärung schließen'],
  ] as const)('explains the status on demand (%s)', async (locale, dismiss) => {
    await renderWithIntl(<ValueStatusBadge status="ESTIMATED" />, locale);
    await fireEvent.press(screen.getByRole('button', { name: /Geschätzt/ }));
    expect(screen.getByText(/beruht auf einer Schätzung/)).toBeTruthy();
    expect(screen.getByRole('button', { name: dismiss })).toBeTruthy();
  });

  it.each([
    ['CURRENT', true],
    ['ESTIMATED', true],
    ['STALE', true],
    ['UNRECONCILED', true],
    [undefined, true],
    ['UNKNOWN', false],
    ['FUTURE_SERVER_ENUM', false],
  ])('treats a value with status %s as known: %s', async (status, known) => {
    expect(isValueKnown(status)).toBe(known);
  });
});

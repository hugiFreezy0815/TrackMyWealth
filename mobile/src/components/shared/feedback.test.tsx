import { fireEvent, screen } from '@testing-library/react-native';

import type { AppLocale } from '@/i18n';
import { renderWithIntl } from '@/i18n/test-utils';

import { ApiError } from '@/api/client';
import {
  BottomSheet,
  ConflictDialog,
  Dialog,
  EmptyState,
  ErrorBanner,
  OfflineBanner,
} from './feedback';

jest.mock('@/global.css', () => ({}));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => ({
    textPrimary: '#0B1C3F',
    textSecondary: '#53627A',
    textOnBrand: '#FFFFFF',
    brandPrimary: '#00857A',
    brandLink: '#006D64',
    statusLoss: '#C42F37',
    statusLossSubtle: '#FDECEC',
    statusWarning: '#8F5200',
    statusWarningSubtle: '#FFF3D9',
    bgSurface: '#FFFFFF',
    bgSubtle: '#ECF1F4',
    bgOverlay: '#0B1C3F99',
  }),
}));

function problem(status: number, code: string, correlationId?: string) {
  return new ApiError('raw server title', status, {
    status,
    title: 'raw server title',
    code,
    correlationId,
  });
}

describe('ErrorBanner', () => {
  it('shows the generic message without a problem', async () => {
    await renderWithIntl(<ErrorBanner />, 'de-CH');
    expect(screen.getByText('Etwas ist schiefgelaufen.')).toBeTruthy();
  });

  it('offers a retry on errors that allow one', async () => {
    const onRetry = jest.fn();
    await renderWithIntl(<ErrorBanner onRetry={onRetry} />, 'en');
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it.each([
    [problem(409, 'VERSION_CONFLICT'), 'en', /changed meanwhile\. Reload it/],
    [problem(403, 'FORBIDDEN'), 'de-CH', /^Das darfst du nicht\.$/],
    [problem(429, 'RATE_LIMITED'), 'en', /Too many requests/],
    // A known code without its own message: the message of its reaction (FIX_INPUT).
    [problem(400, 'INVALID_SORT'), 'en', /Some entries are not valid\. Check them/],
    // An unknown code: the reaction of its status (403 → SHOW).
    [problem(403, 'UNKNOWN_CODE'), 'de-DE', /^Das ist gerade nicht möglich\.$/],
    [problem(500, 'UNKNOWN_CODE', 'corr-1'), 'en', /^Something went wrong\.$/],
  ])('shows the message key of the problem code (%#)', async (error, locale, message) => {
    await renderWithIntl(<ErrorBanner error={error} />, locale as AppLocale);
    expect(screen.getByText(message)).toBeTruthy();
    expect(screen.queryByText(/raw server title/)).toBeNull();
  });

  it('prefers an explicit, user-safe message', async () => {
    await renderWithIntl(<ErrorBanner error={problem(409, 'VERSION_CONFLICT')} message="Custom" />);
    expect(screen.getByText('Custom')).toBeTruthy();
  });

  it('quotes the correlationId for a reported error', async () => {
    await renderWithIntl(<ErrorBanner error={problem(500, 'INTERNAL', 'corr-42')} />, 'en');
    expect(screen.getByText('corr-42')).toBeTruthy();
  });

  it.each([
    [problem(403, 'UNKNOWN_CODE'), false],
    [problem(401, 'UNAUTHENTICATED'), false],
    [problem(400, 'VALIDATION_FAILED'), false],
    [problem(409, 'RETRY'), true],
    [problem(503, 'LOCKED'), true],
    [problem(500, 'INTERNAL'), true],
  ])('offers Retry only when it can help (%#)', async (error, retry) => {
    await renderWithIntl(<ErrorBanner error={error} onRetry={jest.fn()} />, 'en');
    expect(screen.queryByRole('button', { name: 'Try again' }) !== null).toBe(retry);
  });
});

describe('feedback states', () => {
  it('shows the offline last-updated date in the UI locale', async () => {
    await renderWithIntl(<OfflineBanner lastUpdated="2026-11-08" />, 'de-CH');
    expect(screen.getByText('Zuletzt aktualisiert: 08.11.2026')).toBeTruthy();
  });

  it('shows an offline last-updated instant with its time', async () => {
    await renderWithIntl(<OfflineBanner lastUpdated="2026-11-08T12:00:00Z" />);
    expect(screen.getByText(/^Last updated 8 Nov 2026, \d{2}:00$/)).toBeTruthy();
  });

  it('allows an empty-state action', async () => {
    const onAction = jest.fn();
    await renderWithIntl(<EmptyState actionLabel="Add" onAction={onAction} />);
    await fireEvent.press(screen.getByRole('button', { name: 'Add' }));
    expect(onAction).toHaveBeenCalledTimes(1);
  });

  it('does not render a dismissed conflict dialog', async () => {
    await renderWithIntl(
      <ConflictDialog
        visible={false}
        onClose={() => {}}
        onReview={() => {}}
        onDiscard={() => {}}
      />,
    );
    expect(screen.queryByText('This item was changed meanwhile')).toBeNull();
  });

  it('offers review and discard in a visible conflict dialog', async () => {
    const onReview = jest.fn();
    const onDiscard = jest.fn();
    await renderWithIntl(
      <ConflictDialog visible onClose={() => {}} onReview={onReview} onDiscard={onDiscard} />,
      'en',
    );
    await fireEvent.press(screen.getByRole('button', { name: 'Review and save again' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Discard my edit' }));
    expect(onReview).toHaveBeenCalledTimes(1);
    expect(onDiscard).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['Dialog', Dialog],
    ['BottomSheet', BottomSheet],
  ] as const)('%s shows its title and closes', async (_name, Component) => {
    const onClose = jest.fn();
    await renderWithIntl(
      <Component title="Sample" visible onClose={onClose}>
        <></>
      </Component>,
      'de-CH',
    );
    expect(screen.getByRole('header', { name: 'Sample' })).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Schliessen' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

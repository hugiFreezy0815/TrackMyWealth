import { fireEvent, screen } from '@testing-library/react-native';

import { messagesFor, type AppLocale } from '@/i18n';
import { renderWithIntl } from '@/i18n/test-utils';

import { ApiError, SessionRefreshUnavailableError } from '@/api/client';
import { importErrorMessageKey } from '@/features/statement-import/model';
import {
  BottomSheet,
  ConflictDialog,
  Dialog,
  EmptyState,
  ErrorBanner,
  LoadingSkeleton,
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

  it('shows a retryable message when session renewal is temporarily unavailable', async () => {
    const onRetry = jest.fn();
    await renderWithIntl(
      <ErrorBanner error={new SessionRefreshUnavailableError(30)} onRetry={onRetry} />,
      'en',
    );

    expect(screen.getByText('The service is busy. Try again in a moment.')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it.each([
    [problem(409, 'VERSION_CONFLICT'), 'en', /changed meanwhile\. Reload it/],
    [problem(403, 'FORBIDDEN'), 'de-CH', /^Das darfst du nicht\.$/],
    [problem(429, 'RATE_LIMITED'), 'en', /Too many requests/],
    // Spec README §6: NOT_FOUND also covers items the member cannot see.
    [problem(404, 'NOT_FOUND'), 'en', /^This item no longer exists or is not available to you\.$/],
    [
      problem(404, 'NOT_FOUND'),
      'de-CH',
      /^Dieser Eintrag existiert nicht mehr oder ist für dich nicht verfügbar\.$/,
    ],
    [problem(400, 'INVALID_SORT'), 'en', /^This list cannot be sorted that way\.$/],
    [problem(400, 'INVALID_SORT'), 'de-DE', /^Diese Liste kann nicht so sortiert werden\.$/],
    // An unknown code: the reaction of its status (403 → SHOW).
    [problem(403, 'UNKNOWN_CODE'), 'de-DE', /^Das ist gerade nicht möglich\.$/],
    [problem(500, 'UNKNOWN_CODE', 'corr-1'), 'en', /^Something went wrong\.$/],
  ])('shows the message key of the problem code (%#)', async (error, locale, message) => {
    await renderWithIntl(<ErrorBanner error={error} />, locale as AppLocale);
    expect(screen.getByText(message)).toBeTruthy();
    expect(screen.queryByText(/raw server title/)).toBeNull();
  });

  it.each([
    [
      'HOLDING',
      'en',
      'This statement contains a balance or positions that differ from what was recorded for the same date.',
    ],
    [
      'BALANCE',
      'en',
      'This statement contains a balance or positions that differ from what was recorded for the same date.',
    ],
    [
      'HOLDING',
      'de',
      'Der Saldo oder die Positionen dieses Auszugs weichen von den erfassten Daten für dasselbe Datum ab.',
    ],
    [
      'BALANCE',
      'de',
      'Der Saldo oder die Positionen dieses Auszugs weichen von den erfassten Daten für dasselbe Datum ab.',
    ],
  ])(
    'shows localized copy for a %s-only snapshot conflict in %s',
    async (part, locale, expected) => {
      const error = new ApiError('raw server title', 409, {
        status: 409,
        title: 'raw server title',
        code: 'IMPORT_SNAPSHOT_CONFLICT',
        conflicts: [{ part, existingValue: '1.00', statementValue: '2.00' }],
      });
      await renderWithIntl(<ErrorBanner error={error} />, locale);
      expect(screen.getByText(expected)).toBeTruthy();
      const override = importErrorMessageKey(error);
      expect(override).toBe('statement.error.IMPORT_SNAPSHOT_CONFLICT');
      expect(messagesFor(locale)[override ?? '']).toBe(expected);
    },
  );

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
  // react-native-web ignores accessibilityState: the state is in aria props (#534 web check).
  it('announces the loading skeleton as a busy progress bar', async () => {
    await renderWithIntl(<LoadingSkeleton count={2} />);
    const skeleton = screen.getByLabelText('Loading');
    expect(skeleton.props.accessibilityRole).toBe('progressbar');
    expect(skeleton).toBeBusy();
  });

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

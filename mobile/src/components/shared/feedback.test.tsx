import { fireEvent, render, screen } from '@testing-library/react-native';

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
  it('offers a retry on errors that allow one', async () => {
    const onRetry = jest.fn();
    await render(<ErrorBanner onRetry={onRetry} locale="en" />);
    await fireEvent.press(screen.getByRole('button', { name: 'Retry' }));
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
    await render(<ErrorBanner error={error} locale={locale as 'en'} />);
    expect(screen.getByText(message)).toBeTruthy();
    expect(screen.queryByText(/raw server title/)).toBeNull();
  });

  it('prefers an explicit, user-safe message', async () => {
    await render(<ErrorBanner error={problem(409, 'VERSION_CONFLICT')} message="Custom" />);
    expect(screen.getByText('Custom')).toBeTruthy();
  });

  it('quotes the correlationId for a reported error', async () => {
    await render(<ErrorBanner error={problem(500, 'INTERNAL', 'corr-42')} locale="en" />);
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
    await render(<ErrorBanner error={error} onRetry={jest.fn()} locale="en" />);
    expect(screen.queryByRole('button', { name: 'Retry' }) !== null).toBe(retry);
  });
});

describe('feedback states', () => {
  it('shows the offline last-updated date in the UI locale', async () => {
    await render(<OfflineBanner locale="de-CH" lastUpdated="2026-11-08" />);
    expect(screen.getByText('Zuletzt aktualisiert: 08.11.2026')).toBeTruthy();
  });

  it('allows an empty-state action', async () => {
    const onAction = jest.fn();
    await render(<EmptyState actionLabel="Add" onAction={onAction} />);
    await fireEvent.press(screen.getByRole('button', { name: 'Add' }));
    expect(onAction).toHaveBeenCalledTimes(1);
  });

  it('does not render a dismissed conflict dialog', async () => {
    await render(
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
    await render(
      <ConflictDialog
        visible
        locale="en"
        onClose={() => {}}
        onReview={onReview}
        onDiscard={onDiscard}
      />,
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
    await render(
      <Component title="Sample" visible onClose={onClose} locale="de-CH">
        <></>
      </Component>,
    );
    expect(screen.getByRole('header', { name: 'Sample' })).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Schliessen' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

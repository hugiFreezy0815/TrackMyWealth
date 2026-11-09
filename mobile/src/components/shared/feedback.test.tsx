import { fireEvent, render, screen } from '@testing-library/react-native';

import { ApiError } from '@/api/client';
import { ErrorBanner, OfflineBanner, EmptyState, ConflictDialog } from './feedback';

jest.mock('@/global.css', () => ({}));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => ({
    textPrimary: '#0B1C3F', textSecondary: '#53627A', textOnBrand: '#FFFFFF',
    brandPrimary: '#00857A', brandLink: '#006D64', statusLoss: '#C42F37',
    statusLossSubtle: '#FDECEC', statusWarning: '#8F5200', statusWarningSubtle: '#FFF3D9',
    bgSurface: '#FFFFFF', bgSubtle: '#ECF1F4',
  }),
}));
describe('feedback states', () => {
  it('offers a retry on errors that allow one', () => {
    const onRetry = jest.fn();
    render(<ErrorBanner onRetry={onRetry} locale="en" />);
    fireEvent.press(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
  it('does not blindly retry an unknown non-retryable server error', () => {
    render(<ErrorBanner error={new ApiError('internal', 403, { status: 403, title: 'x', code: 'UNKNOWN_CODE' })} onRetry={jest.fn()} />);
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
  });
  it('preserves an offline last-updated indication', () => {
    render(<OfflineBanner locale="de-CH" lastUpdated="2026-11-08" />);
    expect(screen.getByText(/2026-11-08/)).toBeTruthy();
  });
  it('allows an empty-state action', () => {
    const onAction = jest.fn();
    render(<EmptyState actionLabel="Add" onAction={onAction} />);
    fireEvent.press(screen.getByRole('button', { name: 'Add' }));
    expect(onAction).toHaveBeenCalledTimes(1);
  });
  it('does not render a dismissed conflict dialog', () => {
    render(<ConflictDialog visible={false} onClose={() => {}} onReview={() => {}} onDiscard={() => {}} />);
    expect(screen.queryByText('This item was changed meanwhile')).toBeNull();
  });
});

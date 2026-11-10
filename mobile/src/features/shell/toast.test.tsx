import { act, fireEvent, screen } from '@testing-library/react-native';

import { renderWithIntl } from '@/i18n/test-utils';

import { TOAST_DURATION_MS, ToastHost, dismissToast, showToast } from './toast';

jest.mock('@/global.css', () => ({}));

// The store outlives a test; RNTL unmounts the host after each one, so no act is needed here.
afterEach(() => {
  dismissToast();
  jest.useRealTimers();
});

describe('toast (#491)', () => {
  it('shows a catalogue message as a polite status and hides it after a while', async () => {
    jest.useFakeTimers();
    await renderWithIntl(<ToastHost />, 'de');
    expect(screen.queryByTestId('toast')).toBeNull();
    await act(async () => showToast('addAccount.added'));
    const toast = screen.getByTestId('toast');
    expect(toast).toHaveTextContent('Konto hinzugefügt');
    expect(toast.props.accessibilityLiveRegion).toBe('polite');
    await act(async () => {
      jest.advanceTimersByTime(TOAST_DURATION_MS);
    });
    expect(screen.queryByTestId('toast')).toBeNull();
  });

  it('dismisses on tap, and a newer toast replaces the current one', async () => {
    await renderWithIntl(<ToastHost />);
    await act(async () => showToast('addAccount.added'));
    await act(async () => showToast('addAccount.portfolioGone'));
    expect(screen.getByTestId('toast')).toHaveTextContent('This portfolio no longer exists.');
    await fireEvent.press(screen.getByTestId('toast'));
    expect(screen.queryByTestId('toast')).toBeNull();
  });
});

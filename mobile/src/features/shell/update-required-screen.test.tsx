import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { IntlProvider } from 'react-intl';

import { NetworkError } from '@/api/client';

import de from '@/i18n/de.json';
import en from '@/i18n/en.json';

import { UpdateRequiredScreen } from './update-required-screen';

jest.mock('@/global.css', () => ({}));

async function show(
  props: Partial<Parameters<typeof UpdateRequiredScreen>[0]> = {},
  messages: Record<string, string> = en,
  locale = 'en',
) {
  const onRetry = jest.fn();
  const reload = jest.fn();
  const view = await render(
    <IntlProvider locale={locale} messages={messages}>
      <UpdateRequiredScreen onRetry={onRetry} reload={reload} version="1.0.0" {...props} />
    </IntlProvider>,
  );
  return { onRetry, reload, view };
}

describe('UpdateRequiredScreen (#649)', () => {
  it.each(['ios', 'android'] as const)(
    'shows the native instruction and retries without reloading on %s',
    async (platform) => {
      const { onRetry, reload } = await show({ platform });
      await screen.findByRole('header');

      expect(screen.getByRole('header', { name: en['updateRequired.title'] })).toBeTruthy();
      expect(screen.getByText(en['updateRequired.body'])).toBeTruthy();
      expect(screen.getByText('Installed version: 1.0.0')).toBeTruthy();

      fireEvent.press(screen.getByRole('button', { name: en['error.retry'] }));
      expect(onRetry).toHaveBeenCalledTimes(1);
      expect(reload).not.toHaveBeenCalled();
    },
  );

  it('reloads the web document rather than retrying', async () => {
    const { onRetry, reload } = await show({ platform: 'web' });
    await screen.findByRole('header');

    expect(screen.getByText(en['updateRequired.body.web'])).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: en['updateRequired.reload'] }));
    expect(reload).toHaveBeenCalledTimes(1);
    expect(onRetry).not.toHaveBeenCalled();
  });

  it('does not show a version caption when the version is unknown', async () => {
    await show({ platform: 'android', version: '' });
    await screen.findByRole('header');
    expect(screen.queryByText(/Installed version/)).toBeNull();
  });

  it('keeps German informal copy for native and web', async () => {
    const { view } = await show({ platform: 'android' }, de, 'de');
    await screen.findByRole('header');
    expect(screen.getByRole('header', { name: 'Update erforderlich' })).toBeTruthy();
    expect(screen.getByText(/Aktualisiere die App/)).toBeTruthy();
    expect(screen.getByText('Installierte Version: 1.0.0')).toBeTruthy();
    view.unmount();

    await show({ platform: 'web' }, de, 'de');
    expect(screen.getByRole('button', { name: de['updateRequired.reload'] })).toBeTruthy();
  });

  it('disables duplicate taps while an asynchronous native retry is running', async () => {
    let complete: (() => void) | undefined;
    const onRetry = jest.fn(
      () =>
        new Promise<void>((resolve) => {
          complete = resolve;
        }),
    );
    await show({ platform: 'ios', onRetry });
    await screen.findByRole('header');

    const action = screen.getByRole('button', { name: en['error.retry'] });
    fireEvent.press(action);
    fireEvent.press(action);
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(action.props.accessibilityState?.disabled ?? action.props['aria-disabled']).toBe(true);

    await act(async () => complete?.());
    await waitFor(() => {
      const retryButton = screen.getByRole('button', { name: en['error.retry'] });
      expect(retryButton.props['aria-disabled']).toBe(false);
    });
  });

  it('keeps the native retry available after an asynchronous failure', async () => {
    const onRetry = jest
      .fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(undefined);
    await show({ platform: 'android', onRetry });
    await screen.findByRole('header');
    fireEvent.press(screen.getByRole('button', { name: en['error.retry'] }));
    expect(await screen.findByText(en['updateRequired.retryError'])).toBeTruthy();
    await waitFor(() => {
      const retryButton = screen.getByRole('button', { name: en['error.retry'] });
      expect(retryButton.props['aria-disabled']).toBe(false);
    });
    fireEvent.press(screen.getByRole('button', { name: en['error.retry'] }));
    expect(onRetry).toHaveBeenCalledTimes(2);
    await waitFor(() => expect(screen.queryByText(en['updateRequired.retryError'])).toBeNull());
  });

  it('shows localized accessible feedback on network failure and can retry in German', async () => {
    const onRetry = jest.fn().mockRejectedValueOnce(new NetworkError('unreachable'));
    await show({ platform: 'android', onRetry }, de, 'de');
    await screen.findByRole('header');

    fireEvent.press(screen.getByRole('button', { name: de['error.retry'] }));
    const message = await screen.findByText(de['updateRequired.networkError']);
    expect(message.props.accessibilityRole).toBe('alert');
    expect(screen.getByRole('button', { name: de['error.retry'] })).toBeTruthy();

    fireEvent.press(screen.getByRole('button', { name: de['error.retry'] }));
    await waitFor(() => expect(screen.queryByText(de['updateRequired.networkError'])).toBeNull());
    expect(onRetry).toHaveBeenCalledTimes(2);
  });
});

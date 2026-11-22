import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { IntlProvider } from 'react-intl';

import { ApiError, NetworkError } from '@/api/client';

import de from '@/i18n/de.json';
import en from '@/i18n/en.json';

import { UpdateRequiredScreen } from './update-required-screen';

jest.mock('@/global.css', () => ({}));
// A build whose app.json version is missing or malformed has no APP_VERSION.
jest.mock('@/api/app-version', () => ({
  ...jest.requireActual('@/api/app-version'),
  APP_VERSION: undefined,
}));

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

      await fireEvent.press(screen.getByRole('button', { name: en['error.retry'] }));
      expect(onRetry).toHaveBeenCalledTimes(1);
      expect(reload).not.toHaveBeenCalled();
    },
  );

  it('reloads the web document rather than retrying', async () => {
    const { onRetry, reload } = await show({ platform: 'web' });
    await screen.findByRole('header');

    expect(screen.getByText(en['updateRequired.body.web'])).toBeTruthy();
    // axe on web (#649): the page's only heading is level 1.
    expect(screen.getByRole('header', { name: en['updateRequired.title'] })).toHaveProp(
      'aria-level',
      1,
    );
    await fireEvent.press(screen.getByRole('button', { name: en['updateRequired.reload'] }));
    expect(reload).toHaveBeenCalledTimes(1);
    expect(onRetry).not.toHaveBeenCalled();
  });

  it.each([
    ['missing', undefined],
    ['empty', ''],
  ])('does not show a version caption when the version is %s', async (_, version) => {
    await show({ platform: 'android', version });
    await screen.findByRole('header');
    expect(screen.queryByText(/Installed version/)).toBeNull();
  });

  it('says an update is still required when the server keeps refusing the version', async () => {
    const onRetry = jest
      .fn()
      .mockRejectedValueOnce(new ApiError('update required', 426, { code: 'APP_UPDATE_REQUIRED' }));
    await show({ platform: 'ios', onRetry });
    await screen.findByRole('header');
    await fireEvent.press(screen.getByRole('button', { name: en['error.retry'] }));
    expect(await screen.findByText(en['updateRequired.stillRequired'])).toBeTruthy();
    expect(screen.queryByText(en['updateRequired.retryError'])).toBeNull();
  });

  it('keeps German informal copy on Android', async () => {
    await show({ platform: 'android' }, de, 'de');
    await screen.findByRole('header');
    expect(screen.getByRole('header', { name: 'Update erforderlich' })).toBeTruthy();
    expect(screen.getByText(/Aktualisiere die App/)).toBeTruthy();
    expect(screen.getByText('Installierte Version: 1.0.0')).toBeTruthy();
  });

  it('keeps the German reload action on web', async () => {
    await show({ platform: 'web' }, de, 'de');
    await screen.findByRole('header');
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
    await fireEvent.press(action);
    await fireEvent.press(action);
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(action.props.accessibilityState?.disabled ?? action.props['aria-disabled']).toBe(true);

    await act(async () => complete?.());
    await waitFor(() => {
      const retryButton = screen.getByRole('button', { name: en['error.retry'] });
      expect(retryButton).toBeEnabled();
    });
  });

  it('keeps the native retry available after an asynchronous failure', async () => {
    const onRetry = jest
      .fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(undefined);
    await show({ platform: 'android', onRetry });
    await screen.findByRole('header');
    await fireEvent.press(screen.getByRole('button', { name: en['error.retry'] }));
    expect(await screen.findByText(en['updateRequired.retryError'])).toBeTruthy();
    await waitFor(() => {
      const retryButton = screen.getByRole('button', { name: en['error.retry'] });
      expect(retryButton).toBeEnabled();
    });
    await fireEvent.press(screen.getByRole('button', { name: en['error.retry'] }));
    expect(onRetry).toHaveBeenCalledTimes(2);
    await waitFor(() => expect(screen.queryByText(en['updateRequired.retryError'])).toBeNull());
  });

  it('shows localized accessible feedback on network failure and can retry in German', async () => {
    const onRetry = jest.fn().mockRejectedValueOnce(new NetworkError('unreachable'));
    await show({ platform: 'android', onRetry }, de, 'de');
    await screen.findByRole('header');

    await fireEvent.press(screen.getByRole('button', { name: de['error.retry'] }));
    const message = await screen.findByText(de['updateRequired.networkError']);
    expect(message.props.accessibilityRole).toBe('alert');
    // The only alert: never nested inside another live region.
    for (let node = message.parent; node; node = node.parent) {
      expect(node.props.accessibilityRole).not.toBe('alert');
    }
    expect(screen.getByRole('button', { name: de['error.retry'] })).toBeTruthy();

    await fireEvent.press(screen.getByRole('button', { name: de['error.retry'] }));
    await waitFor(() => expect(screen.queryByText(de['updateRequired.networkError'])).toBeNull());
    expect(onRetry).toHaveBeenCalledTimes(2);
  });
});

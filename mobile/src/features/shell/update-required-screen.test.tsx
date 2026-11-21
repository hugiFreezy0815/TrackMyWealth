import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { IntlProvider } from 'react-intl';
import { Platform } from 'react-native';

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
    await show({ platform: 'android' }, de, 'de');
    await screen.findByRole('header');
    expect(screen.getByRole('header', { name: 'Update erforderlich' })).toBeTruthy();
    expect(screen.getByText(/Aktualisiere die App/)).toBeTruthy();
    expect(screen.getByText('Installierte Version: 1.0.0')).toBeTruthy();
    screen.unmount();

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

    const action = screen.getByRole('button', { name: en['error.retry'] });
    fireEvent.press(action);
    fireEvent.press(action);
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(action.props.accessibilityState?.disabled ?? action.props['aria-disabled']).toBe(true);

    await act(async () => complete?.());
    await waitFor(() => expect(screen.getByRole('button', { name: en['error.retry'] })).toBeEnabled());
  });

  it('keeps the native retry available after an asynchronous failure', async () => {
    const onRetry = jest.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(undefined);
    await show({ platform: 'android', onRetry });
    fireEvent.press(screen.getByRole('button', { name: en['error.retry'] }));
    await waitFor(() => expect(screen.getByRole('button', { name: en['error.retry'] })).toBeEnabled());
    fireEvent.press(screen.getByRole('button', { name: en['error.retry'] }));
    expect(onRetry).toHaveBeenCalledTimes(2);
  });
});

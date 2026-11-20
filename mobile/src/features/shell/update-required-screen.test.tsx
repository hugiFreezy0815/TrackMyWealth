import { fireEvent, render, screen } from '@testing-library/react-native';
import { IntlProvider } from 'react-intl';

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

describe('UpdateRequiredScreen (#576)', () => {
  it('asks a native member to update from the store and lets them try again', async () => {
    const { onRetry, reload } = await show({ platform: 'ios' });
    await screen.findByRole('header');

    expect(screen.getByRole('header', { name: en['updateRequired.title'] })).toBeTruthy();
    expect(screen.getByText(en['updateRequired.body'])).toBeTruthy();
    expect(screen.getByText('Installed version: 1.0.0')).toBeTruthy();

    fireEvent.press(screen.getByRole('button', { name: en['error.retry'] }));
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(reload).not.toHaveBeenCalled();
  });

  it('reloads the page on web, which loads the current build', async () => {
    const { onRetry, reload } = await show({ platform: 'web' });
    await screen.findByRole('header');

    expect(screen.getByText(en['updateRequired.body.web'])).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: en['updateRequired.reload'] }));
    expect(reload).toHaveBeenCalledTimes(1);
    expect(onRetry).not.toHaveBeenCalled();
  });

  it('leaves the version out when the build has none', async () => {
    await show({ platform: 'android', version: undefined });
    await screen.findByRole('header');

    expect(screen.queryByText(/Installed version/)).toBeNull();
  });

  it('speaks German with du', async () => {
    await show({ platform: 'android' }, de, 'de');
    await screen.findByRole('header');

    expect(screen.getByRole('header', { name: 'Update erforderlich' })).toBeTruthy();
    expect(screen.getByText(/Aktualisiere die App/)).toBeTruthy();
    expect(screen.getByText('Installierte Version: 1.0.0')).toBeTruthy();
  });
});

import { render, screen } from '@testing-library/react-native';
import { onlineManager } from '@tanstack/react-query';
import { IntlProvider } from 'react-intl';

import messages from '@/i18n/en.json';

import { OfflineBanner } from './offline-banner';

jest.mock('@/global.css', () => ({}));

afterEach(() => onlineManager.setOnline(true));
it('shows the global offline banner when disconnected', async () => {
  onlineManager.setOnline(false);
  await render(
    <IntlProvider locale="en" messages={messages}>
      <OfflineBanner />
    </IntlProvider>,
  );
  expect(screen.getByRole('alert')).toBeTruthy();
  expect(screen.getByText(messages['error.offline'])).toBeTruthy();
});
it('does not show an offline banner while connected', async () => {
  onlineManager.setOnline(true);
  await render(
    <IntlProvider locale="en" messages={messages}>
      <OfflineBanner />
    </IntlProvider>,
  );
  expect(screen.queryByRole('alert')).toBeNull();
});

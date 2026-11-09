// Settings route (M13/W09): the Hide amounts switch and sign-out. Lives outside src/app, where
// expo-router would treat a test file as a route.
import { fireEvent, renderRouter, screen } from 'expo-router/testing-library';

import SettingsRoute from '@/app/settings';
import { signOut } from '@/features/auth/session';
import { replaceDocument } from '@/features/auth/web-document';
import { amountsHidden, setAmountsHidden } from '@/features/privacy/amount-visibility';
import { withIntl } from '@/i18n/test-utils';

jest.mock('@/global.css', () => ({}));
jest.mock('@/features/overview-name/settings-row', () => ({ OverviewNameSettings: () => null }));
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async () => null),
  setItemAsync: jest.fn(async () => {}),
}));
jest.mock('@/features/auth/session', () => ({ signOut: jest.fn(async () => {}) }));
jest.mock('@/features/auth/web-document', () => ({ replaceDocument: jest.fn() }));

const routes = {
  _layout: () => withIntl(<SettingsRoute />),
  settings: () => null,
  overview: () => null,
  'accounts/[accountId]/index': () => null,
};

beforeEach(async () => {
  jest.mocked(signOut).mockClear();
  await setAmountsHidden(false);
});

describe('Settings', () => {
  it('turns Hide amounts on for every screen', async () => {
    await renderRouter(routes, { initialUrl: '/settings' });

    await fireEvent(screen.getByRole('switch', { name: 'Hide amounts' }), 'valueChange', true);

    expect(amountsHidden()).toBe(true);
    expect(screen.getByRole('switch', { name: 'Hide amounts' }).props.value).toBe(true);
  });

  it('leaves the previous member screen when signing out', async () => {
    const view = await renderRouter(routes, { initialUrl: '/accounts/acc-1' });

    await fireEvent.press(screen.getByRole('button', { name: 'Sign out' }));

    expect(signOut).toHaveBeenCalledTimes(1);
    // The next person signing in on this device starts on Overview, not on that account.
    expect(view.getPathname()).toBe('/overview');
    // Only after the session is cleared: a fresh web document keeps nothing in memory.
    expect(replaceDocument).toHaveBeenCalledWith('/overview');
    expect(jest.mocked(replaceDocument).mock.invocationCallOrder[0]).toBeGreaterThan(
      jest.mocked(signOut).mock.invocationCallOrder[0],
    );
  });
});

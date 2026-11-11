// US-01-13 AC2: a deep link opened while signed out goes through sign-in (and MFA) first and
// opens afterwards. Uses the real root layout (src/app/_layout.tsx); tests cannot live in src/app,
// where expo-router would treat them as routes.
import { act, fireEvent, renderRouter, screen } from 'expo-router/testing-library';

import { usePortfolios, useWorkspace } from '@/api/queries/resources';
import RootLayout from '@/app/_layout';
import { loadServerUrl } from '@/api/server-url';
import type { Identity } from '@/features/auth/session';
import { UnavailableScreen } from '@/features/shell/screen';

type Session = { signedIn: boolean; identity: Identity | null; mfaRequired: boolean };

const mockSession: { current: Session; listeners: Set<() => void> } = {
  current: { signedIn: false, identity: null, mfaRequired: false },
  listeners: new Set(),
};

jest.mock('@/global.css', () => ({}));
jest.mock('@/api/queries/resources', () => ({
  useWorkspace: jest.fn(),
  usePortfolios: jest.fn(),
}));
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async () => null),
  setItemAsync: jest.fn(async () => {}),
}));
jest.mock('expo-screen-capture', () => ({
  enableAppSwitcherProtectionAsync: jest.fn(async () => {}),
  disableAppSwitcherProtectionAsync: jest.fn(async () => {}),
  preventScreenCaptureAsync: jest.fn(async () => {}),
  allowScreenCaptureAsync: jest.fn(async () => {}),
}));
jest.mock('@react-native-community/netinfo', () => ({ addEventListener: jest.fn(() => () => {}) }));
jest.mock('expo-splash-screen', () => ({ preventAutoHideAsync: jest.fn(), hideAsync: jest.fn() }));
jest.mock('@/components/animated-icon', () => ({ AnimatedSplashOverlay: () => null }));
jest.mock('@/api/server-url', () => ({
  ...jest.requireActual('@/api/server-url'),
  loadServerUrl: jest.fn(async () => {}),
}));
jest.mock('@/features/auth/session', () => ({
  ...jest.requireActual('@/features/auth/session'),
  restoreSession: jest.fn(async () => {}),
  sessionSnapshot: () => mockSession.current,
  subscribeSession: (listener: () => void) => {
    mockSession.listeners.add(listener);
    return () => mockSession.listeners.delete(listener);
  },
}));

const accountPath = '/accounts/acc-1';
const accountText = 'This screen is not available yet';
const workspace = { id: 'w-1', name: null, currency: 'CHF', version: 1 };
const MEMBER: Identity = { userId: 'm', role: 'STANDARD_USER', language: 'EN', sessionId: 's1' };

async function serverSays(next: Partial<Session>) {
  await act(async () => {
    mockSession.current = { ...mockSession.current, ...next };
    mockSession.listeners.forEach((listener) => listener());
  });
}

const routes = {
  _layout: RootLayout,
  overview: () => null,
  'accounts/[accountId]/index': UnavailableScreen,
};

beforeEach(() => {
  mockSession.current = { signedIn: false, identity: null, mfaRequired: false };
  jest.mocked(useWorkspace).mockReturnValue({
    data: workspace,
    isPending: false,
    isError: false,
  } as ReturnType<typeof useWorkspace>);
  jest.mocked(usePortfolios).mockReturnValue({
    data: [{ personalAssetsDefault: false, activeAccountCount: 1 }],
    isPending: false,
    isError: false,
  } as ReturnType<typeof usePortfolios>);
});

describe('deep link while signed out (US-01-13 AC2)', () => {
  it.each([
    { flow: 'with MFA', requiresMfa: true },
    { flow: 'without MFA', requiresMfa: false },
  ])('opens a returning member’s linked account after sign-in $flow', async ({ requiresMfa }) => {
    const view = await renderRouter(routes, { initialUrl: accountPath });

    expect(await screen.findByRole('header')).toHaveTextContent('Sign in');
    expect(screen.queryByText(accountText)).toBeNull();

    if (requiresMfa) {
      await serverSays({ mfaRequired: true });
      expect(screen.getByRole('header')).toHaveTextContent('Verify sign-in');
    }

    // Token acceptance precedes the response from /users/me.
    await serverSays({ signedIn: true, mfaRequired: false });
    expect(view.getPathname()).toBe(accountPath);

    jest.mocked(useWorkspace).mockReturnValueOnce({
      isPending: true,
      isError: false,
    } as ReturnType<typeof useWorkspace>);
    await serverSays({ identity: MEMBER });
    expect(screen.queryByText(accountText)).toBeNull();
    expect(view.getPathname()).toBe(accountPath);

    await serverSays({});
    expect(await screen.findByText(accountText)).toBeTruthy();
    expect(view.getPathname()).toBe(accountPath);
  });

  it.each(['Continue', 'Skip for now'])(
    'opens the account when a first-run member chooses %s',
    async (action) => {
      jest.mocked(usePortfolios).mockReturnValue({
        data: [],
        isPending: false,
        isError: false,
      } as unknown as ReturnType<typeof usePortfolios>);
      const view = await renderRouter(routes, { initialUrl: accountPath });
      expect(await screen.findByRole('header')).toHaveTextContent('Sign in');

      await serverSays({ signedIn: true });
      await serverSays({ identity: MEMBER });
      const setupAction = await screen.findByRole('button', { name: action });
      expect(screen.queryByText(accountText)).toBeNull();
      expect(view.getPathname()).toBe(accountPath);

      await fireEvent.press(setupAction);
      expect(await screen.findByText(accountText)).toBeTruthy();
      expect(view.getPathname()).toBe(accountPath);
    },
  );

  it('opens the account on a signed-in cold start', async () => {
    mockSession.current = { signedIn: true, identity: MEMBER, mfaRequired: false };
    const view = await renderRouter(routes, { initialUrl: accountPath });

    expect(await screen.findByText(accountText)).toBeTruthy();
    expect(view.getPathname()).toBe(accountPath);
  });

  it('announces the busy progress bar while restoring the session', async () => {
    let finishRestoring = () => {};
    jest.mocked(loadServerUrl).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishRestoring = resolve;
      }),
    );
    await renderRouter(routes, { initialUrl: '/overview' });

    expect(screen.getByRole('progressbar', { name: 'Restoring session…' })).toBeBusy();

    await act(async () => finishRestoring());
    expect(await screen.findByRole('header')).toHaveTextContent('Sign in');
    expect(screen.queryByRole('progressbar')).toBeNull();
  });

  it('never renders a signed-in screen before the session is confirmed', async () => {
    await renderRouter(routes, { initialUrl: accountPath });
    await screen.findByRole('header');
    expect(screen.queryByRole('tab')).toBeNull();
    expect(screen.queryByText(accountText)).toBeNull();
  });
});

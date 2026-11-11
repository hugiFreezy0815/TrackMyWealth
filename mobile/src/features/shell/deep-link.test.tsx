// US-01-13 AC2: a deep link opened while signed out goes through sign-in (and MFA) first and
// opens afterwards. Uses the real root layout (src/app/_layout.tsx); tests cannot live in src/app,
// where expo-router would treat them as routes.
import { act, renderRouter, screen } from 'expo-router/testing-library';

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
jest.mock('@/features/overview-name/setup-gate', () => ({
  OverviewSetupGate: ({ children }: { children: React.ReactNode }) => children,
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
});

describe('deep link while signed out (US-01-13 AC2)', () => {
  it('asks to sign in first and opens the linked account afterwards, also through MFA', async () => {
    const view = await renderRouter(routes, { initialUrl: '/accounts/acc-1' });

    expect(await screen.findByRole('header')).toHaveTextContent('Sign in');
    expect(screen.queryByText('This screen is not available yet')).toBeNull();

    await serverSays({ mfaRequired: true });
    expect(screen.getByRole('header')).toHaveTextContent('Verify sign-in');

    await serverSays({ signedIn: true, mfaRequired: false, identity: MEMBER });

    expect(await screen.findByText('This screen is not available yet')).toBeTruthy();
    expect(view.getPathname()).toBe('/accounts/acc-1');
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
    await renderRouter(routes, { initialUrl: '/accounts/acc-1' });
    await screen.findByRole('header');
    expect(screen.queryByRole('tab')).toBeNull();
    expect(screen.queryByText('This screen is not available yet')).toBeNull();
  });
});

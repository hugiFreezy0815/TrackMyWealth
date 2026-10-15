import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

import { api, setAccessToken } from '@/api/client';
import {
  cancelMfa,
  isAdministrator,
  restoreSession,
  sessionSnapshot,
  signIn,
  signOut,
  verifyMfa,
} from './session';

jest.mock('expo-secure-store', () => ({
  WHEN_UNLOCKED_THIS_DEVICE_ONLY: 'when-unlocked-this-device-only',
  setItemAsync: jest.fn(async () => {}),
  getItemAsync: jest.fn(async () => null),
  deleteItemAsync: jest.fn(async () => {}),
}));

const secureStore = jest.mocked(SecureStore);

function tokens(n: number) {
  return {
    accessToken: `access-${n}`,
    refreshToken: `refresh-${n}`,
    tokenType: 'Bearer',
    expiresInSeconds: 900,
  };
}

type Reply = { status: number; body?: unknown } | Error;
type Route = (url: string, init: RequestInit) => Reply | Promise<Reply>;

/** A fake server: each request is answered by the route, and every call is recorded. */
function serve(route: Route) {
  const fetchMock = jest.fn(async (url: string, init: RequestInit) => {
    const reply = await route(url, init);
    if (reply instanceof Error) throw reply;
    return {
      ok: reply.status >= 200 && reply.status < 300,
      status: reply.status,
      json: async () => reply.body,
    };
  });
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  return fetchMock;
}

async function waitForCall(fetchMock: jest.Mock, suffix: string) {
  while (callsTo(fetchMock, suffix).length === 0) {
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
}

function bodyOf(init: RequestInit) {
  return JSON.parse(String(init.body));
}

function callsTo(fetchMock: jest.Mock, suffix: string) {
  return fetchMock.mock.calls.filter(([url]) => String(url).endsWith(suffix));
}

const MEMBER = {
  userId: 'member',
  role: 'STANDARD_USER',
  language: 'EN',
  sessionId: 'this-session',
} as const;

/** Signs in without MFA; the access token then expires on the server. */
function plainLogin(url: string): Reply | undefined {
  if (url.endsWith('/auth/login'))
    return { status: 200, body: { mfaRequired: false, tokens: tokens(1) } };
  if (url.endsWith('/users/me')) return { status: 200, body: MEMBER };
  return undefined;
}

describe('secure auth session', () => {
  beforeEach(async () => {
    Platform.OS = 'ios';
    jest.clearAllMocks();
    // What the app does on start: installs the refresh handler (nothing is stored yet).
    await restoreSession();
  });

  afterEach(async () => {
    serve(() => ({ status: 200, body: [] }));
    await signOut();
    setAccessToken(null);
  });

  it('stores the refresh token in device-only secure storage and verifies the identity', async () => {
    serve((url) => plainLogin(url) ?? { status: 404 });

    await signIn('demo@example.com', 'test');

    expect(secureStore.setItemAsync).toHaveBeenCalledWith(
      expect.any(String),
      'refresh-1',
      expect.objectContaining({ keychainAccessible: 'when-unlocked-this-device-only' }),
    );
    expect(sessionSnapshot()).toMatchObject({ signedIn: true, identity: MEMBER });
  });

  it('knows an administrator only from the identity the server verified', async () => {
    serve((url) =>
      url.endsWith('/users/me')
        ? { status: 200, body: { ...MEMBER, role: 'SYSTEM_ADMINISTRATOR' } }
        : (plainLogin(url) ?? { status: 404 }),
    );

    await signIn('admin@example.com', 'test');

    expect(isAdministrator(sessionSnapshot().identity)).toBe(true);
    expect(isAdministrator(MEMBER)).toBe(false);
    expect(isAdministrator(null)).toBe(false);
  });

  it('uses an MFA challenge without considering it an authenticated session', async () => {
    const fetchMock = serve((url) => {
      if (url.endsWith('/auth/login'))
        return {
          status: 200,
          body: { mfaRequired: true, mfaChallengeToken: 'challenge', tokens: null },
        };
      if (url.endsWith('/mfa/verify')) return { status: 200, body: tokens(1) };
      return { status: 200, body: MEMBER };
    });

    await signIn('demo@example.com', 'test');
    expect(sessionSnapshot()).toMatchObject({ mfaRequired: true, signedIn: false });

    await verifyMfa('123456');

    expect(bodyOf(callsTo(fetchMock, '/mfa/verify')[0][1])).toEqual({
      challengeToken: 'challenge',
      code: '123456',
    });
    expect(sessionSnapshot()).toMatchObject({ mfaRequired: false, signedIn: true });
  });

  it('lets the user leave an MFA challenge that keeps being rejected', async () => {
    serve((url) =>
      url.endsWith('/auth/login')
        ? { status: 200, body: { mfaRequired: true, mfaChallengeToken: 'expired', tokens: null } }
        : { status: 401, body: { code: 'UNAUTHENTICATED' } },
    );
    await signIn('demo@example.com', 'test');

    await expect(verifyMfa('123456')).rejects.toMatchObject({ status: 401 });
    expect(sessionSnapshot().mfaRequired).toBe(true);

    cancelMfa();

    expect(sessionSnapshot()).toMatchObject({ mfaRequired: false, signedIn: false });
  });

  it('never persists tokens on web', async () => {
    Platform.OS = 'web';
    serve((url) => plainLogin(url) ?? { status: 404 });

    await signIn('demo@example.com', 'test');

    expect(secureStore.setItemAsync).not.toHaveBeenCalled();
    expect(sessionSnapshot().signedIn).toBe(true);
  });

  it('on a 401 rotates once, stores the new refresh token and retries with the new access token', async () => {
    const fetchMock = serve((url, init) => {
      if (url.endsWith('/auth/refresh')) return { status: 200, body: tokens(2) };
      if (url.endsWith('/accounts')) {
        const auth = (init.headers as Record<string, string>).Authorization;
        return auth === 'Bearer access-2' ? { status: 200, body: ['ok'] } : { status: 401 };
      }
      return plainLogin(url) ?? { status: 404 };
    });
    await signIn('demo@example.com', 'test');

    await expect(api.get('/api/v1/accounts')).resolves.toEqual(['ok']);

    expect(bodyOf(callsTo(fetchMock, '/auth/refresh')[0][1])).toEqual({
      refreshToken: 'refresh-1',
    });
    expect(secureStore.setItemAsync).toHaveBeenLastCalledWith(
      expect.any(String),
      'refresh-2',
      expect.anything(),
    );
  });

  it('rotates only once when several requests hit an expired token at the same time', async () => {
    let refreshed = false;
    const fetchMock = serve((url, init) => {
      if (url.endsWith('/auth/refresh')) {
        refreshed = true;
        return { status: 200, body: tokens(2) };
      }
      if (url.endsWith('/accounts')) {
        const auth = (init.headers as Record<string, string>).Authorization;
        return refreshed && auth === 'Bearer access-2'
          ? { status: 200, body: [] }
          : { status: 401 };
      }
      return plainLogin(url) ?? { status: 404 };
    });
    await signIn('demo@example.com', 'test');

    await Promise.all([
      api.get('/api/v1/accounts'),
      api.get('/api/v1/accounts'),
      api.get('/api/v1/accounts'),
    ]);

    expect(callsTo(fetchMock, '/auth/refresh')).toHaveLength(1);
  });

  it('returns to sign-in and forgets the stored token when the server rejects the refresh', async () => {
    serve((url) => {
      if (url.endsWith('/auth/refresh')) return { status: 401, body: { code: 'UNAUTHENTICATED' } };
      if (url.endsWith('/accounts')) return { status: 401 };
      return plainLogin(url) ?? { status: 404 };
    });
    await signIn('demo@example.com', 'test');

    await expect(api.get('/api/v1/accounts')).rejects.toMatchObject({ status: 401 });

    expect(sessionSnapshot()).toMatchObject({ signedIn: false, identity: null });
    expect(secureStore.deleteItemAsync).toHaveBeenCalled();
  });

  it('keeps the session when the refresh fails only because the server is unreachable', async () => {
    serve((url) => {
      if (url.endsWith('/auth/refresh')) return new TypeError('Network request failed');
      if (url.endsWith('/accounts')) return { status: 401 };
      return plainLogin(url) ?? { status: 404 };
    });
    await signIn('demo@example.com', 'test');

    await expect(api.get('/api/v1/accounts')).rejects.toMatchObject({ status: 401 });

    expect(sessionSnapshot().signedIn).toBe(true);
    expect(secureStore.deleteItemAsync).not.toHaveBeenCalled();
  });

  it('restores a stored session on start by rotating its refresh token', async () => {
    secureStore.getItemAsync.mockResolvedValueOnce('refresh-stored');
    const fetchMock = serve((url) => {
      if (url.endsWith('/auth/refresh')) return { status: 200, body: tokens(2) };
      if (url.endsWith('/users/me')) return { status: 200, body: MEMBER };
      return { status: 404 };
    });

    await restoreSession();

    expect(bodyOf(callsTo(fetchMock, '/auth/refresh')[0][1])).toEqual({
      refreshToken: 'refresh-stored',
    });
    expect(sessionSnapshot()).toMatchObject({ signedIn: true, identity: MEMBER });
  });

  it('stays signed in when started offline with a stored session', async () => {
    secureStore.getItemAsync.mockResolvedValueOnce('refresh-stored');
    serve(() => new TypeError('Network request failed'));

    await restoreSession();

    expect(sessionSnapshot().signedIn).toBe(true);
    expect(secureStore.deleteItemAsync).not.toHaveBeenCalled();
  });

  it('signs out by revoking the session the server named, without listing all sessions', async () => {
    serve((url) => plainLogin(url) ?? { status: 404 });
    await signIn('demo@example.com', 'test');
    const fetchMock = serve(() => ({ status: 200, body: {} }));

    await signOut();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(callsTo(fetchMock, '/sessions/this-session/revoke')).toHaveLength(1);
    expect(secureStore.deleteItemAsync).toHaveBeenCalled();
    expect(sessionSnapshot()).toMatchObject({ signedIn: false, identity: null });
  });

  it('asks the server for the session id when signing out before the identity was verified', async () => {
    secureStore.getItemAsync.mockResolvedValueOnce('refresh-stored');
    serve(() => new TypeError('Network request failed'));
    await restoreSession();
    expect(sessionSnapshot()).toMatchObject({ signedIn: true, identity: null });
    const fetchMock = serve((url) => {
      if (url.endsWith('/auth/refresh')) return { status: 200, body: tokens(2) };
      if (url.endsWith('/users/me')) return { status: 200, body: MEMBER };
      return { status: 200, body: {} };
    });

    await signOut();

    expect(callsTo(fetchMock, '/sessions/this-session/revoke')).toHaveLength(1);
    expect(sessionSnapshot().signedIn).toBe(false);
  });

  it('ends a restored session the server rejects right after renewing it, instead of hanging', async () => {
    // e.g. a deactivated workspace member against a backend that still renewed their token.
    secureStore.getItemAsync.mockResolvedValueOnce('refresh-stored');
    const fetchMock = serve((url) =>
      url.endsWith('/auth/refresh') ? { status: 200, body: tokens(2) } : { status: 401 },
    );

    await restoreSession();

    expect(callsTo(fetchMock, '/auth/refresh')).toHaveLength(1);
    expect(sessionSnapshot()).toMatchObject({ signedIn: false, identity: null });
    expect(secureStore.deleteItemAsync).toHaveBeenCalled();
  });

  it('fails sign-in when the server rejects the token it has just issued', async () => {
    const fetchMock = serve((url) =>
      url.endsWith('/auth/login')
        ? { status: 200, body: { mfaRequired: false, tokens: tokens(1) } }
        : { status: 401 },
    );

    await expect(signIn('demo@example.com', 'test')).rejects.toMatchObject({ status: 401 });

    expect(callsTo(fetchMock, '/auth/refresh')).toHaveLength(0);
    expect(sessionSnapshot().signedIn).toBe(false);
  });

  it('retries with the new session when a sign-in replaces the one being renewed', async () => {
    let finishRefresh: (reply: Reply) => void = () => undefined;
    let logins = 0;
    const fetchMock = serve((url, init) => {
      if (url.endsWith('/auth/login')) {
        logins += 1;
        return { status: 200, body: { mfaRequired: false, tokens: tokens(logins === 1 ? 1 : 3) } };
      }
      if (url.endsWith('/auth/refresh')) return new Promise<Reply>((r) => (finishRefresh = r));
      if (url.endsWith('/users/me')) return { status: 200, body: MEMBER };
      const auth = (init.headers as Record<string, string>).Authorization;
      return auth === 'Bearer access-3' ? { status: 200, body: ['ok'] } : { status: 401 };
    });
    await signIn('demo@example.com', 'test');

    const request = api.get('/api/v1/accounts');
    await waitForCall(fetchMock, '/auth/refresh');
    await signIn('demo@example.com', 'test');
    finishRefresh({ status: 200, body: tokens(2) });

    await expect(request).resolves.toEqual(['ok']);
    expect(secureStore.setItemAsync).toHaveBeenLastCalledWith(
      expect.any(String),
      'refresh-3',
      expect.anything(),
    );
  });

  it('still signs out on this device when the server cannot be reached', async () => {
    serve((url) => plainLogin(url) ?? { status: 404 });
    await signIn('demo@example.com', 'test');
    serve(() => new TypeError('Network request failed'));

    await expect(signOut()).resolves.toBeUndefined();

    expect(secureStore.deleteItemAsync).toHaveBeenCalled();
    expect(sessionSnapshot().signedIn).toBe(false);
  });
});

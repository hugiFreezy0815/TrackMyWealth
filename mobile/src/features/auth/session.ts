import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

import {
  ApiError,
  SessionRefreshUnavailableError,
  type SessionRefreshResult,
  setAccessToken,
  setRefreshHandler,
} from '@/api/client';
import { problemReaction } from '@/api/problems';
import type { components } from '@/api/schema';
import { typedApi } from '@/api/typed-api';
import { queryClient } from '@/api/queries/client';

type Tokens = components['schemas']['AuthTokensResponse'];
export type Identity = components['schemas']['CurrentUserResponse'];
type Listener = () => void;

const STORAGE_KEY = 'trackmywealth.session.refresh';
const DEFAULT_REFRESH_DELAY_SECONDS = 5;
// Readable only while the device is unlocked, never restored onto another device from a backup.
const STORAGE_OPTIONS: SecureStore.SecureStoreOptions = {
  keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
};
const listeners = new Set<Listener>();
let access: string | null = null;
let refresh: string | null = null;
let identity: Identity | null = null;
// True while GET /users/me for freshly accepted tokens is in flight.
let verifyingIdentity = false;
let challenge: string | null = null;
let pendingRefresh: Promise<SessionRefreshResult> | null = null;
let refreshNotBefore = 0;
let generation = 0;

function changed() {
  listeners.forEach((listener) => listener());
}

export function subscribeSession(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * Signed in while a refresh token is held, even without a current access token: started offline,
 * the app keeps the session and the first request renews it once the server is reachable.
 * `verifyingIdentity` is true while the server is still being asked who the new tokens belong to;
 * when it cannot answer, the session goes on without an identity.
 */
export function sessionSnapshot() {
  return { signedIn: !!refresh, identity, mfaRequired: !!challenge, verifyingIdentity };
}

/** Only for showing or hiding admin screens; the server enforces the role on every admin call. */
export function isAdministrator(current: Identity | null): boolean {
  return current?.role === 'SYSTEM_ADMINISTRATOR';
}

async function persist(token: string | null): Promise<void> {
  // Web never writes either token to any browser storage.
  if (Platform.OS === 'web') return;
  if (token) await SecureStore.setItemAsync(STORAGE_KEY, token, STORAGE_OPTIONS);
  else await SecureStore.deleteItemAsync(STORAGE_KEY, STORAGE_OPTIONS);
}

// Only the server saying no ends a session: an expired, reused or revoked refresh token asks to
// sign in again (UNAUTHENTICATED, 401), a malformed one to fix the input (VALIDATION_FAILED, 400);
// a code this app does not know counts by its status (#375). Offline, a timeout or a 5xx keeps the
// stored token for the next try.
function rejectedByServer(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false;
  const { reaction } = problemReaction(error);
  return reaction === 'REAUTHENTICATE' || reaction === 'FIX_INPUT';
}

/**
 * Takes over freshly issued tokens and verifies who they belong to. Throws when the server rejects
 * the token it has just issued: such a session is unusable, so it ends here and the caller (sign-in
 * or rotation) fails instead of announcing it.
 */
async function accept(tokens: Tokens): Promise<void> {
  const current = ++generation;
  // On native, do not announce the new session until the rotated refresh token is durable.
  await persist(tokens.refreshToken);
  if (current !== generation) return;
  refresh = tokens.refreshToken;
  refreshNotBefore = 0;
  access = tokens.accessToken;
  setAccessToken(access);
  verifyingIdentity = true;
  changed();
  let verified: Identity;
  try {
    // Never through the refresh handler: this can run inside a rotation, which would then wait for
    // itself. A 401 here is final - renewing a token the server has only just issued cannot help.
    verified = await typedApi.get('/api/v1/users/me', { renewSession: false });
  } catch (error) {
    if (current === generation && rejectedByServer(error)) {
      await clearSession();
      throw error;
    }
    // Unreachable: the session still exists, and without a verified identity no admin controls
    // are shown. The next rotation verifies it again.
    if (current === generation) {
      verifyingIdentity = false;
      changed();
    }
    return;
  }
  if (current === generation) {
    identity = verified;
    verifyingIdentity = false;
    changed();
  }
}

export async function signIn(email: string, password: string): Promise<void> {
  challenge = null;
  const result = await typedApi.post('/api/v1/auth/login', { body: { email, password } });
  if (result.mfaRequired) {
    if (!result.mfaChallengeToken) throw new Error('MFA challenge missing');
    challenge = result.mfaChallengeToken;
    changed();
  } else if (result.tokens) {
    await accept(result.tokens);
  } else {
    throw new Error('Login response did not contain a session');
  }
}

export async function verifyMfa(code: string): Promise<void> {
  if (!challenge) throw new Error('No MFA challenge');
  const tokens = await typedApi.post('/api/v1/auth/mfa/verify', {
    body: { challengeToken: challenge, code },
  });
  challenge = null;
  await accept(tokens);
}

/**
 * Leaves the MFA step for the password form. A wrong code and an expired challenge are the same
 * 401 by design, so the user decides when to start over.
 */
export function cancelMfa(): void {
  if (!challenge) return;
  challenge = null;
  changed();
}

async function rotate(): Promise<SessionRefreshResult> {
  if (!refresh) return null;
  const remainingSeconds = (refreshNotBefore - Date.now()) / 1000;
  if (remainingSeconds > 0) return new SessionRefreshUnavailableError(remainingSeconds);
  const before = generation;
  try {
    const tokens = await typedApi.post('/api/v1/auth/refresh', { body: { refreshToken: refresh } });
    // A sign-in or sign-out replaced this session meanwhile: the waiting request uses whatever
    // holds now (null after a sign-out), never the tokens of the session that was replaced.
    if (before !== generation) return access;
    await accept(tokens);
    return access;
  } catch (error) {
    if (before !== generation) return access;
    if (rejectedByServer(error)) {
      await clearSession();
      return access;
    }
    const now = Date.now();
    const retryAfter = error instanceof ApiError ? error.retryAfterSeconds : undefined;
    const delay =
      retryAfter !== undefined && now + retryAfter * 1000 <= Number.MAX_SAFE_INTEGER
        ? retryAfter
        : DEFAULT_REFRESH_DELAY_SECONDS;
    refreshNotBefore = now + delay * 1000;
    return new SessionRefreshUnavailableError(delay);
  }
}

function refreshSession(): Promise<SessionRefreshResult> {
  if (!pendingRefresh) {
    pendingRefresh = rotate().finally(() => {
      pendingRefresh = null;
    });
  }
  return pendingRefresh;
}

/**
 * Call once when the app starts, also on web: from here on a 401 renews the session, and on native
 * a session stored by an earlier start is restored by rotating its refresh token.
 */
export async function restoreSession(): Promise<void> {
  setRefreshHandler(refreshSession);
  if (Platform.OS === 'web') return;
  const stored = await SecureStore.getItemAsync(STORAGE_KEY, STORAGE_OPTIONS);
  if (!stored || refresh) return;
  refresh = stored;
  changed();
  await refreshSession();
}

async function clearSession(): Promise<void> {
  generation++;
  refresh = null;
  refreshNotBefore = 0;
  access = null;
  challenge = null;
  identity = null;
  verifyingIdentity = false;
  setAccessToken(null);
  // Drop the member's financial data: stop requests in flight, then empty the cache.
  void queryClient.cancelQueries();
  queryClient.clear();
  changed();
  try {
    await persist(null);
  } catch {
    // Signed out in memory regardless. A token left behind after a successful revoke is dead on
    // the server; restoring it later just fails its refresh and returns to sign-in.
  }
}

export async function signOut(): Promise<void> {
  // Revoke this session on the server first, while its token still works. If that fails (e.g.
  // offline), the local credentials are still removed and the server session simply expires.
  try {
    if (refresh) {
      // Started offline, the identity (and with it the session id) is not verified yet.
      const sessionId = identity?.sessionId ?? (await typedApi.get('/api/v1/users/me')).sessionId;
      await typedApi.post('/api/v1/sessions/{id}/revoke', { params: { id: sessionId } });
    }
  } catch {
    // Nothing to tell the user: they are signed out on this device either way.
  } finally {
    await clearSession();
  }
}

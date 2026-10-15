import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

import { api, ApiError, setAccessToken, setRefreshHandler } from '@/api/client';

export type Tokens = {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresInSeconds: number;
};
export type Identity = { userId: string; role: string; language: string };
export type LoginResult = {
  mfaRequired: boolean;
  mfaChallengeToken: string | null;
  tokens: Tokens | null;
};
type Listener = () => void;

const STORAGE_KEY = 'trackmywealth.session.refresh';
// Readable only while the device is unlocked, never restored onto another device from a backup.
const STORAGE_OPTIONS: SecureStore.SecureStoreOptions = {
  keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
};
const listeners = new Set<Listener>();
let access: string | null = null;
let refresh: string | null = null;
let identity: Identity | null = null;
let challenge: string | null = null;
let pendingRefresh: Promise<string | null> | null = null;
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
 */
export function sessionSnapshot() {
  return { signedIn: !!refresh, identity, mfaRequired: !!challenge };
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

async function accept(tokens: Tokens): Promise<void> {
  const current = ++generation;
  // On native, do not announce the new session until the rotated refresh token is durable.
  await persist(tokens.refreshToken);
  if (current !== generation) return;
  refresh = tokens.refreshToken;
  access = tokens.accessToken;
  setAccessToken(access);
  changed();
  try {
    const verified = await api.get<Identity>('/api/v1/users/me');
    if (current === generation) {
      identity = verified;
      changed();
    }
  } catch {
    // The session still exists: without a verified identity no admin controls are shown.
  }
}

export async function signIn(email: string, password: string): Promise<void> {
  challenge = null;
  const result = await api.post<LoginResult>('/api/v1/auth/login', { email, password });
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
  const tokens = await api.post<Tokens>('/api/v1/auth/mfa/verify', {
    challengeToken: challenge,
    code,
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

// Only the server saying no ends a session: an expired, reused or revoked refresh token is a 401
// (a malformed one a 400). Offline, a timeout or a 5xx keeps the stored token for the next try.
function rejectedByServer(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 400 || error.status === 401);
}

async function rotate(): Promise<string | null> {
  if (!refresh) return null;
  const before = generation;
  try {
    const tokens = await api.post<Tokens>('/api/v1/auth/refresh', { refreshToken: refresh });
    if (before !== generation) return null;
    await accept(tokens);
    return access;
  } catch (error) {
    if (before === generation && rejectedByServer(error)) await clearSession();
    return null;
  }
}

export function refreshSession(): Promise<string | null> {
  if (!pendingRefresh) {
    pendingRefresh = rotate().finally(() => {
      pendingRefresh = null;
    });
  }
  return pendingRefresh;
}

setRefreshHandler(refreshSession);

export async function restoreSession(): Promise<void> {
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
  access = null;
  challenge = null;
  identity = null;
  setAccessToken(null);
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
    const sessions = await api.get<{ id: string; current: boolean }[]>('/api/v1/sessions');
    const current = sessions.find((entry) => entry.current);
    if (current) await api.post(`/api/v1/sessions/${current.id}/revoke`);
  } catch {
    // Nothing to tell the user: they are signed out on this device either way.
  } finally {
    await clearSession();
  }
}

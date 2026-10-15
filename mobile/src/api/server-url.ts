import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

import { getApiBaseUrl, setApiBaseUrl } from './client';

const STORAGE_KEY = 'trackmywealth.server.url';

// Plain HTTP only reaches this device or the Android emulator's host: credentials and tokens
// must never cross a network unencrypted, so every other server needs HTTPS (NFR-SEC-*).
const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]', '10.0.2.2']);

// Parsed by hand: React Native's URL implementation does not support every getter on all
// platforms. Credentials, query and fragment are rejected rather than silently dropped.
const SERVER_URL = /^(https?):\/\/(\[[0-9a-f:]+\]|[a-z0-9.-]+)(:\d{1,5})?(\/[^\s?#@]*)?$/i;

export class InvalidServerUrlError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'InvalidServerUrlError';
  }
}

/** FR-CLI-007: the API base URL of a self-hosted deployment, normalized without a trailing slash. */
export function normalizeServerUrl(input: string): string {
  const match = SERVER_URL.exec(input.trim());
  if (!match) {
    throw new InvalidServerUrlError('Enter the server address, e.g. https://wealth.example.com');
  }
  const [, scheme, host, port = '', path = ''] = match;
  const protocol = scheme.toLowerCase();
  const hostname = host.toLowerCase();
  if (protocol === 'http' && !LOOPBACK_HOSTS.has(hostname)) {
    throw new InvalidServerUrlError('The server must use https://');
  }
  return `${protocol}://${hostname}${port}${path.replace(/\/+$/, '')}`;
}

async function read(): Promise<string | null> {
  if (Platform.OS === 'web') {
    try {
      return globalThis.localStorage?.getItem(STORAGE_KEY) ?? null;
    } catch {
      return null;
    }
  }
  return SecureStore.getItemAsync(STORAGE_KEY);
}

async function write(url: string): Promise<void> {
  // Not a secret, so the web build may remember it; tokens never go here.
  if (Platform.OS === 'web') {
    try {
      globalThis.localStorage?.setItem(STORAGE_KEY, url);
    } catch {
      // Private browsing: the address simply has to be entered again next time.
    }
    return;
  }
  await SecureStore.setItemAsync(STORAGE_KEY, url);
}

/** Applies a previously saved server address; an unreadable or invalid one keeps the default. */
export async function loadServerUrl(): Promise<void> {
  try {
    const stored = await read();
    if (stored) setApiBaseUrl(normalizeServerUrl(stored));
  } catch {
    // Keep the build's default address.
  }
}

export async function saveServerUrl(input: string): Promise<string> {
  const url = normalizeServerUrl(input);
  if (url !== getApiBaseUrl()) {
    setApiBaseUrl(url);
    await write(url);
  }
  return url;
}

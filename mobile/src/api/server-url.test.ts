import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

import { api, getApiBaseUrl, setApiBaseUrl } from './client';
import { loadServerUrl, normalizeServerUrl, saveServerUrl } from './server-url';

jest.mock('expo-secure-store', () => ({
  setItemAsync: jest.fn(async () => {}),
  getItemAsync: jest.fn(async () => null),
}));

const secureStore = jest.mocked(SecureStore);

// __DEV__ is a constant to the app; tests switch it to see what a release build accepts.
function setDevBuild(value: boolean) {
  (globalThis as unknown as { __DEV__: boolean }).__DEV__ = value;
}
const DEFAULT_URL = getApiBaseUrl();

describe('configurable server address (FR-CLI-007)', () => {
  beforeEach(() => {
    Platform.OS = 'ios';
    jest.clearAllMocks();
    setApiBaseUrl(DEFAULT_URL);
  });

  it.each([
    ['https://wealth.example.com', 'https://wealth.example.com'],
    ['  HTTPS://Wealth.Example.com:8443/api/ ', 'https://wealth.example.com:8443/api'],
    ['http://localhost:8080', 'http://localhost:8080'],
    ['http://[::1]:8080', 'http://[::1]:8080'],
  ])('accepts %p', (input, expected) => {
    expect(normalizeServerUrl(input)).toBe(expected);
  });

  it.each([
    'http://wealth.example.com',
    'http://192.168.1.20:8080',
    // Not the emulator's host outside Android: an ordinary address in 10.0.0.0/8.
    'http://10.0.2.2:8080',
    'wealth.example.com',
    'ftp://wealth.example.com',
    'https://user:secret@wealth.example.com',
    'https://wealth.example.com/?next=evil',
    'https://wealth.example.com/#x',
    '',
  ])('rejects %p', (input) => {
    expect(() => normalizeServerUrl(input)).toThrow();
  });

  describe('the Android emulator host over plain HTTP', () => {
    const devBuild = __DEV__;
    afterEach(() => {
      setDevBuild(devBuild);
    });

    it('is accepted in an Android development build', () => {
      Platform.OS = 'android';
      setDevBuild(true);

      expect(normalizeServerUrl('http://10.0.2.2:8080')).toBe('http://10.0.2.2:8080');
    });

    it('is rejected in a release build, where it may be a real network address', () => {
      Platform.OS = 'android';
      setDevBuild(false);

      expect(() => normalizeServerUrl('http://10.0.2.2:8080')).toThrow('https://');
    });
  });

  it('uses and remembers a saved address on native', async () => {
    await saveServerUrl('https://wealth.example.com/');

    expect(getApiBaseUrl()).toBe('https://wealth.example.com');
    expect(secureStore.setItemAsync).toHaveBeenCalledWith(
      expect.any(String),
      'https://wealth.example.com',
    );
  });

  it('applies the remembered address on start', async () => {
    secureStore.getItemAsync.mockResolvedValueOnce('https://wealth.example.com');

    await loadServerUrl();

    expect(getApiBaseUrl()).toBe('https://wealth.example.com');
  });

  it('keeps the default when the remembered address is no longer valid', async () => {
    secureStore.getItemAsync.mockResolvedValueOnce('http://insecure.example.com');

    await loadServerUrl();

    expect(getApiBaseUrl()).toBe(DEFAULT_URL);
  });

  it('does not change the address when the input is invalid', async () => {
    await expect(saveServerUrl('http://insecure.example.com')).rejects.toThrow('https://');

    expect(getApiBaseUrl()).toBe(DEFAULT_URL);
    expect(secureStore.setItemAsync).not.toHaveBeenCalled();
  });

  it('sends requests to the configured server', async () => {
    const fetchMock = jest.fn().mockResolvedValue({ ok: true, status: 204 });
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    await saveServerUrl('https://wealth.example.com');

    await api.get('/api/v1/accounts');

    expect(fetchMock.mock.calls[0][0]).toBe('https://wealth.example.com/api/v1/accounts');
  });
});

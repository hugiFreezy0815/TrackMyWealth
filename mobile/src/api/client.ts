// FR-CLI-007: the build's default, replaced by the server a user configured (see server-url.ts).
let apiBaseUrl = process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080';

export function getApiBaseUrl(): string {
  return apiBaseUrl;
}

export function setApiBaseUrl(url: string): void {
  apiBaseUrl = url;
}

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly body?: unknown,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** The server could not be reached or did not answer in time, so there is no HTTP status. */
export class NetworkError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'NetworkError';
  }
}

// Without a deadline, a network that silently drops packets keeps sign-in or session restore
// waiting for the platform's own timeout - a minute or more - with nothing the user can do.
export const REQUEST_TIMEOUT_MS = 15_000;

/**
 * An exact API decimal - money, quantity, unit price, FX rate, percentage - exactly as the backend
 * sends it: a plain decimal string such as "12345678.1234567891" (#176). Declare such fields with
 * this type, never `number`: a JavaScript number is an IEEE-754 double and rounds the value. Keep
 * it as received, send it back unchanged, and do arithmetic only through a decimal library.
 */
export type DecimalString = string;

type RequestOptions = Omit<RequestInit, 'body'> & {
  body?: unknown;
  // false: a 401 is final and never goes through the refresh handler (see session.ts, which
  // verifies a token the server has only just issued).
  renewSession?: boolean;
};

function withIfMatch(version: number, options: RequestOptions = {}): RequestOptions {
  return {
    ...options,
    headers: {
      ...options.headers,
      'If-Match': `"${version}"`,
    },
  };
}

// Owned by features/auth/session.ts, which sets it after sign-in and every rotation.
let accessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

// Called on a 401 to rotate the session; resolves to the new access token, or null when the
// session could not be renewed. The login/refresh/MFA endpoints under /api/v1/auth/ never retry.
let refreshHandler: (() => Promise<string | null>) | null = null;

export function setRefreshHandler(handler: (() => Promise<string | null>) | null): void {
  refreshHandler = handler;
}

async function send(url: string, init: RequestInit): Promise<Response> {
  const timeout = new AbortController();
  const timer = setTimeout(() => timeout.abort(), REQUEST_TIMEOUT_MS);
  try {
    return await fetch(url, { ...init, signal: init.signal ?? timeout.signal });
  } catch (failure) {
    // A caller's own abort stays what it is; everything else means the server was not reached.
    if (init.signal?.aborted) throw failure;
    throw new NetworkError(
      timeout.signal.aborted
        ? `${init.method ?? 'GET'} ${url} timed out`
        : `${init.method ?? 'GET'} ${url} could not reach the server`,
    );
  } finally {
    clearTimeout(timer);
  }
}

async function request<T>(path: string, options: RequestOptions = {}, retried = false): Promise<T> {
  const { body, headers, renewSession = true, ...rest } = options;
  const sentToken = accessToken;

  const response = await send(`${apiBaseUrl}${path}`, {
    ...rest,
    headers: {
      'Content-Type': 'application/json',
      ...(sentToken ? { Authorization: `Bearer ${sentToken}` } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (
    response.status === 401 &&
    renewSession &&
    !retried &&
    refreshHandler &&
    !path.startsWith('/api/v1/auth/')
  ) {
    // Another request already rotated the token while this one was in flight: just resend.
    const renewed = accessToken && accessToken !== sentToken ? accessToken : await refreshHandler();
    if (renewed) return request<T>(path, options, true);
  }

  if (!response.ok) {
    const errorBody = await response.json().catch(() => undefined);
    throw new ApiError(
      `${options.method ?? 'GET'} ${path} failed with ${response.status}`,
      response.status,
      errorBody,
    );
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

export const api = {
  get: <T>(path: string, options?: RequestOptions) =>
    request<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: 'PUT', body }),
  putVersioned: <T>(path: string, version: number, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...withIfMatch(version, options), method: 'PUT', body }),
  postVersioned: <T>(path: string, version: number, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...withIfMatch(version, options), method: 'POST', body }),
  patchVersioned: <T>(path: string, version: number, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...withIfMatch(version, options), method: 'PATCH', body }),
  delete: <T>(path: string, options?: RequestOptions) =>
    request<T>(path, { ...options, method: 'DELETE' }),
  deleteVersioned: <T>(path: string, version: number, options?: RequestOptions) =>
    request<T>(path, { ...withIfMatch(version, options), method: 'DELETE' }),
};

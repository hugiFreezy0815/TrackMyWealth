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

/**
 * An exact API decimal - money, quantity, unit price, FX rate, percentage - exactly as the backend
 * sends it: a plain decimal string such as "12345678.1234567891" (#176). Declare such fields with
 * this type, never `number`: a JavaScript number is an IEEE-754 double and rounds the value. Keep
 * it as received, send it back unchanged, and do arithmetic only through a decimal library.
 */
export type DecimalString = string;

type RequestOptions = Omit<RequestInit, 'body'> & { body?: unknown };

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

async function request<T>(path: string, options: RequestOptions = {}, retried = false): Promise<T> {
  const { body, headers, ...rest } = options;
  const sentToken = accessToken;

  const response = await fetch(`${apiBaseUrl}${path}`, {
    ...rest,
    headers: {
      'Content-Type': 'application/json',
      ...(sentToken ? { Authorization: `Bearer ${sentToken}` } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (response.status === 401 && !retried && refreshHandler && !path.startsWith('/api/v1/auth/')) {
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

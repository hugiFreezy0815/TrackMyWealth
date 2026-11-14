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
    public readonly retryAfterSeconds?: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** The session still exists, but its access token cannot be renewed yet. */
export class SessionRefreshUnavailableError extends ApiError {
  constructor(retryAfterSeconds: number) {
    super('Session renewal is temporarily unavailable', 503, undefined, retryAfterSeconds);
    this.name = 'SessionRefreshUnavailableError';
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

export type { DecimalString } from './decimal';

export type RequestOptions = Omit<RequestInit, 'body'> & {
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

// Called on a 401 to rotate the session. Null means there is no remaining session to renew.
// The login/refresh/MFA endpoints under /api/v1/auth/ never retry.
export type SessionRefreshResult = string | SessionRefreshUnavailableError | null;
let refreshHandler: (() => Promise<SessionRefreshResult>) | null = null;

export function setRefreshHandler(handler: (() => Promise<SessionRefreshResult>) | null): void {
  refreshHandler = handler;
}

// A statement upload is answered only once the server has parsed the file (US-07-04): up to 5 MB
// on a slow mobile uplink plus PDF or spreadsheet parsing outlasts the JSON deadline above, and a
// client that gives up early leaves the batch the server still creates as an open batch.
export const UPLOAD_TIMEOUT_MS = 120_000;

async function send(url: string, init: RequestInit, timeoutMs: number): Promise<Response> {
  // One controller ends the request on the deadline or on the caller's own abort (an upload's
  // Cancel); AbortSignal.any is missing on Hermes.
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const callerAbort = () => controller.abort();
  if (init.signal?.aborted) controller.abort();
  init.signal?.addEventListener('abort', callerAbort);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } catch (failure) {
    // A caller's own abort stays what it is; everything else means the server was not reached.
    if (init.signal?.aborted) throw failure;
    throw new NetworkError(
      timedOut
        ? `${init.method ?? 'GET'} ${url} timed out`
        : `${init.method ?? 'GET'} ${url} could not reach the server`,
    );
  } finally {
    clearTimeout(timer);
    init.signal?.removeEventListener('abort', callerAbort);
  }
}

/**
 * RFC 9110 `Retry-After`: delay-seconds or an HTTP-date. Returns seconds from now (never negative),
 * or undefined when the header is missing or unreadable.
 */
export function parseRetryAfter(
  header: string | null | undefined,
  now = Date.now(),
): number | undefined {
  const value = header?.trim();
  if (!value) return undefined;
  if (/^\d+$/.test(value)) return Number(value);
  // Only an HTTP-date (it names a weekday); Date.parse also reads "2" or "2.5" as a year.
  if (!/^[A-Za-z]{3},/.test(value)) return undefined;
  const at = Date.parse(value);
  return Number.isNaN(at) ? undefined : Math.max(0, (at - now) / 1000);
}

type ExchangeOptions = Omit<RequestInit, 'body' | 'headers'> & {
  headers?: Record<string, string>;
  body?: BodyInit;
  renewSession?: boolean;
  timeoutMs?: number;
};

/**
 * One authenticated exchange, shared by every transport: the access token, one renewal of the
 * session on a 401 (never for /api/v1/auth/), ApiError with the problem body and Retry-After, and
 * NetworkError when the server was not reached in time.
 */
async function exchange(path: string, options: ExchangeOptions): Promise<Response> {
  const { headers, renewSession = true, timeoutMs = REQUEST_TIMEOUT_MS, ...init } = options;
  const url = `${apiBaseUrl}${path}`;
  const attempt = (token: string | null) =>
    send(
      url,
      { ...init, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers } },
      timeoutMs,
    );
  const sentToken = accessToken;
  let response = await attempt(sentToken);

  if (
    response.status === 401 &&
    renewSession &&
    refreshHandler &&
    !path.startsWith('/api/v1/auth/')
  ) {
    // Another request already rotated the token while this one was in flight: just resend.
    const renewed = accessToken && accessToken !== sentToken ? accessToken : await refreshHandler();
    if (renewed instanceof SessionRefreshUnavailableError) throw renewed;
    if (renewed) response = await attempt(accessToken);
  }

  if (!response.ok) {
    const errorBody = await response.json().catch(() => undefined);
    throw new ApiError(
      `${init.method ?? 'GET'} ${path} failed with ${response.status}`,
      response.status,
      errorBody,
      parseRetryAfter(response.headers?.get?.('Retry-After')),
    );
  }
  return response;
}

/**
 * The JSON transport every API call goes through. Features call typedApi (typed-api.ts), whose
 * types come from the contract; they never name a path or a type by hand.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { body, headers, ...rest } = options;
  const response = await exchange(path, {
    ...rest,
    headers: { 'Content-Type': 'application/json', ...(headers as Record<string, string>) },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export type MultipartOptions = Pick<RequestOptions, 'renewSession' | 'signal'> & {
  body: FormData;
  timeoutMs?: number;
};

/**
 * A multipart upload answered with JSON (typedApi.upload). It never sets Content-Type: the
 * runtime writes the multipart boundary into it.
 */
export async function requestMultipart<T>(path: string, options: MultipartOptions): Promise<T> {
  const response = await exchange(path, {
    ...options,
    method: 'POST',
    timeoutMs: options.timeoutMs ?? UPLOAD_TIMEOUT_MS,
  });
  return (await response.json()) as T;
}

/** A download answered as text, such as a CSV export (typedApi.text). */
export async function requestText(
  path: string,
  options: Pick<RequestOptions, 'renewSession' | 'signal'> = {},
): Promise<string> {
  const response = await exchange(path, { ...options, method: 'GET' });
  return response.text();
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

const API_URL = process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080';

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

// Placeholder token accessor - replace with the real session store once
// EPIC-02 (auth) lands on the backend and a client-side auth store exists here.
let accessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { body, headers, ...rest } = options;

  const response = await fetch(`${API_URL}${path}`, {
    ...rest,
    headers: {
      'Content-Type': 'application/json',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

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
  delete: <T>(path: string, options?: RequestOptions) =>
    request<T>(path, { ...options, method: 'DELETE' }),
  deleteVersioned: <T>(path: string, version: number, options?: RequestOptions) =>
    request<T>(path, { ...withIfMatch(version, options), method: 'DELETE' }),
};

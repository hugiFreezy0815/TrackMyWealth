import { request, type RequestOptions } from './client';
import type { paths } from './schema';

/**
 * US-29-05 (#375): API calls typed by the committed contract. The path picks the operation from
 * the generated `paths` (schema.d.ts), and with it the path parameters, the query, the JSON
 * request body, the If-Match version and the success response - so a backend change shows up here
 * as a type error. Transport (token refresh, timeouts, NetworkError, ApiError) is client.ts's.
 *
 *   const me = await typedApi.get('/api/v1/users/me');
 *   await typedApi.put('/api/v1/accounts/{accountId}/identifier/from-import', {
 *     params: { accountId },
 *     version,
 *     body: { importAccountId, batchId },
 *   });
 */

type HttpMethod = 'get' | 'post' | 'put' | 'patch' | 'delete';

/** The paths that have an operation for `M`. */
export type PathWith<M extends HttpMethod> = {
  [P in keyof paths]: paths[P] extends Record<M, unknown> ? P : never;
}[keyof paths];

type Operation<P extends keyof paths, M extends HttpMethod> = NonNullable<paths[P][M]>;

type IsNever<T> = [T] extends [never] ? true : false;

type Parameters<O> = O extends { parameters: infer Declared } ? Declared : Record<string, never>;

type ParameterIn<O, K extends string> = K extends keyof Parameters<O>
  ? NonNullable<Parameters<O>[K]>
  : never;

type JsonBody<O> = O extends { requestBody?: { content: { 'application/json': infer B } } }
  ? B
  : never;

type HeaderNames<O> = keyof ParameterIn<O, 'header'>;

// An operation that requires If-Match takes the version it protects; every other one takes none.
type VersionOption<O> =
  'If-Match' extends HeaderNames<O>
    ? undefined extends ParameterIn<O, 'header'>['If-Match' & HeaderNames<O>]
      ? { version?: number }
      : { version: number }
    : { version?: never };

type PathOption<O> =
  IsNever<ParameterIn<O, 'path'>> extends true
    ? { params?: never }
    : { params: ParameterIn<O, 'path'> };

type QueryOption<O> =
  IsNever<ParameterIn<O, 'query'>> extends true
    ? { query?: never }
    : undefined extends Parameters<O>['query' & keyof Parameters<O>]
      ? { query?: ParameterIn<O, 'query'> }
      : { query: ParameterIn<O, 'query'> };

type BodyOption<O> =
  IsNever<JsonBody<O>> extends true
    ? { body?: never }
    : O extends { requestBody: unknown }
      ? { body: JsonBody<O> }
      : { body?: JsonBody<O> };

/** What a call to `path` with `M` takes, besides the path itself. */
export type CallOptions<P extends keyof paths, M extends HttpMethod> = PathOption<Operation<P, M>> &
  QueryOption<Operation<P, M>> &
  BodyOption<Operation<P, M>> &
  VersionOption<Operation<P, M>> &
  Pick<RequestOptions, 'renewSession' | 'signal'>;

type SuccessStatus = 200 | 201 | 202 | 204;

type ContentOf<R> = R extends { content: { 'application/json': infer B } }
  ? B
  : R extends { content?: never }
    ? undefined
    : never;

/** The body a successful call to `path` with `M` answers: `undefined` for a 204. */
export type ResponseBody<P extends keyof paths, M extends HttpMethod> =
  Operation<P, M> extends { responses: infer Responses }
    ? {
        [S in keyof Responses]: S extends SuccessStatus ? ContentOf<Responses[S]> : never;
      }[keyof Responses]
    : never;

// The options are optional exactly when nothing in them is required.
type OptionsArgument<T> = Record<string, never> extends T ? [options?: T] : [options: T];

function pathWith(template: string, params: Record<string, unknown> | undefined): string {
  return template.replace(/\{([^}]+)\}/g, (_, name: string) => {
    const value = params?.[name];
    if (value === undefined || value === null) {
      throw new Error(`Path parameter ${name} of ${template} is missing`);
    }
    return encodeURIComponent(String(value));
  });
}

function queryString(query: Record<string, unknown> | undefined): string {
  if (!query) return '';
  const search = new URLSearchParams();
  for (const [name, value] of Object.entries(query)) {
    if (value === undefined || value === null) continue;
    for (const item of Array.isArray(value) ? value : [value]) {
      search.append(name, String(item));
    }
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

type UntypedOptions = {
  params?: Record<string, unknown>;
  query?: Record<string, unknown>;
  body?: unknown;
  version?: number;
} & Pick<RequestOptions, 'renewSession' | 'signal'>;

function call<M extends HttpMethod>(method: M) {
  return <P extends PathWith<M>>(
    path: P,
    ...options: OptionsArgument<CallOptions<P, M>>
  ): Promise<ResponseBody<P, M>> => {
    // The signature checked the options against the operation; inside, they are plain values
    // (and reading them through their generic type would expand it over every path).
    const { params, query, body, version, renewSession, signal }: UntypedOptions =
      (options as unknown[])[0] ?? {};
    return request<ResponseBody<P, M>>(pathWith(path, params) + queryString(query), {
      method: method.toUpperCase(),
      body,
      renewSession,
      signal,
      headers: version === undefined ? undefined : { 'If-Match': `"${version}"` },
    });
  };
}

export const typedApi = {
  get: call('get'),
  post: call('post'),
  put: call('put'),
  patch: call('patch'),
  delete: call('delete'),
};

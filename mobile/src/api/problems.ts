import { ApiError } from './client';
import { IMPORT_ROW_CODES, PROBLEM_CODE_REACTIONS } from './problem-codes.generated';
import type { components } from './schema';

/**
 * US-29-05 (#375): what the app does with an error response. Problem codes are an open set (#374):
 * the server may send a code this app version does not know, so every switch over the known codes
 * has a fallback, and an unknown code is handled by the response's HTTP status.
 */

export type ApiProblem = components['schemas']['ApiProblem'];

/** A problem code this app version knows (api/problem-codes.json). */
export type KnownProblemCode = keyof typeof PROBLEM_CODE_REACTIONS;
/** A problem code: a known one, or one added to the server after this app was built. */
export type ProblemCode = KnownProblemCode | (string & {});

/** An import row code this app version knows. */
export type KnownImportRowCode = (typeof IMPORT_ROW_CODES)[number];
/** An import row code: a known one, or a newer one - show the row's `message` for that. */
export type ImportRowCode = KnownImportRowCode | (string & {});

/** What the client does about a problem (docs/api/error-codes.md, "Client reactions"). */
export type ClientReaction = (typeof PROBLEM_CODE_REACTIONS)[KnownProblemCode];

export function isKnownProblemCode(code: string): code is KnownProblemCode {
  return Object.prototype.hasOwnProperty.call(PROBLEM_CODE_REACTIONS, code);
}

export function isKnownImportRowCode(code: string): code is KnownImportRowCode {
  return (IMPORT_ROW_CODES as readonly string[]).includes(code);
}

/**
 * The reaction to a response with no code this app knows, by its status (#375): 401 refreshes
 * the token or signs in again, 403 shows "not allowed", 409 and 412 reload the current state,
 * another 4xx shows the status's generic error, and anything else is reported with its
 * correlationId. Never a retry: an unknown code must not repeat a request that is not idempotent.
 */
export function reactionForStatus(status: number): ClientReaction {
  if (status === 401) return 'REAUTHENTICATE';
  if (status === 403) return 'SHOW';
  if (status === 409 || status === 412) return 'RELOAD';
  if (status >= 400 && status < 500) return 'SHOW';
  return 'REPORT';
}

/** The problem body of an error response, when it is one. */
export function problemOf(error: ApiError): ApiProblem | undefined {
  const body = error.body;
  if (typeof body !== 'object' || body === null) return undefined;
  const { code, status, title } = body as Partial<ApiProblem>;
  return typeof code === 'string' && typeof status === 'number' && typeof title === 'string'
    ? (body as ApiProblem)
    : undefined;
}

export type ProblemReaction = {
  reaction: ClientReaction;
  /** The code when this app knows it; undefined for an unknown code or a response without one. */
  code: KnownProblemCode | undefined;
  /** The code the server sent, known or not. */
  sentCode: ProblemCode | undefined;
  status: number;
  /** To quote when reporting the error. */
  correlationId: string | undefined;
};

/** What to do about `error`: its code's reaction when the code is known, else by its status. */
export function problemReaction(error: ApiError): ProblemReaction {
  const problem = problemOf(error);
  const sentCode = problem?.code;
  const code = sentCode !== undefined && isKnownProblemCode(sentCode) ? sentCode : undefined;
  return {
    reaction: code ? PROBLEM_CODE_REACTIONS[code] : reactionForStatus(error.status),
    code,
    sentCode,
    status: error.status,
    correlationId: problem?.correlationId,
  };
}

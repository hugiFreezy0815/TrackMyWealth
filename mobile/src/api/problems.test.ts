import { ApiError } from './client';
import {
  isKnownImportRowCode,
  isKnownProblemCode,
  problemOf,
  problemReaction,
  type ClientReaction,
  type ImportRowCode,
  type KnownProblemCode,
  type ProblemCode,
} from './problems';

// A code added to the server after this app version was built.
const MADE_UP = 'SOMETHING_NEW_IN_A_LATER_RELEASE';

function problem(status: number, code: string, correlationId?: string): ApiError {
  return new ApiError(`failed with ${status}`, status, {
    title: 'Problem',
    status,
    code,
    ...(correlationId ? { correlationId } : {}),
  });
}

describe('problemReaction', () => {
  it.each<[number, ClientReaction]>([
    [401, 'REAUTHENTICATE'],
    [403, 'SHOW'],
    [409, 'RELOAD'],
    [412, 'RELOAD'],
    [400, 'SHOW'],
    [404, 'SHOW'],
    [422, 'SHOW'],
    [429, 'SHOW'],
    [500, 'REPORT'],
    [503, 'REPORT'],
  ])('falls back to the status for an unknown code: %i -> %s', (status, reaction) => {
    const result = problemReaction(problem(status, MADE_UP, 'corr-1'));

    expect(result).toEqual({
      reaction,
      code: undefined,
      sentCode: MADE_UP,
      status,
      correlationId: 'corr-1',
    });
  });

  it('never answers an unknown code with a retry, whatever the status', () => {
    for (let status = 400; status < 600; status++) {
      expect(problemReaction(problem(status, MADE_UP)).reaction).not.toMatch(/RETRY/);
    }
  });

  it("uses a known code's own reaction, not its status's", () => {
    // 409, but the contract says retry right away (a lost race) - not the 409 fallback "reload".
    expect(problemReaction(problem(409, 'RETRY'))).toMatchObject({
      reaction: 'RETRY',
      code: 'RETRY',
    });
    expect(problemReaction(problem(412, 'VERSION_CONFLICT')).reaction).toBe('RELOAD_AND_RETRY');
    expect(problemReaction(problem(503, 'IMPORT_PDF_BUSY')).reaction).toBe('RETRY_LATER');
  });

  it('falls back to the status for a body that is no problem', () => {
    expect(problemReaction(new ApiError('x', 403, { internal: 'hidden' }))).toEqual({
      reaction: 'SHOW',
      code: undefined,
      sentCode: undefined,
      status: 403,
      correlationId: undefined,
    });
    expect(problemOf(new ApiError('x', 500))).toBeUndefined();
    expect(problemOf(new ApiError('x', 500, 'text'))).toBeUndefined();
  });
});

describe('the code types', () => {
  it('know the contract codes and accept an unknown one', () => {
    const known: ProblemCode = 'VERSION_CONFLICT';
    const unknown: ProblemCode = MADE_UP;
    const row: ImportRowCode = MADE_UP;

    expect(isKnownProblemCode(known)).toBe(true);
    expect(isKnownProblemCode(unknown)).toBe(false);
    expect(isKnownProblemCode('toString')).toBe(false);
    expect(isKnownImportRowCode('IMPORT_ROW_ZERO_AMOUNT')).toBe(true);
    expect(isKnownImportRowCode(row)).toBe(false);
  });

  it('allow an exhaustive switch over the known codes with a fallback for the rest', () => {
    // The guard narrows to the closed union of known codes, so a switch can name each of them
    // (and the compiler rejects a code that is no known one); an unknown code takes the fallback.
    function describeCode(code: ProblemCode): string {
      if (!isKnownProblemCode(code)) return 'by status';
      switch (code) {
        case 'UNAUTHENTICATED':
          return 'sign in';
        default: {
          const rest: Exclude<KnownProblemCode, 'UNAUTHENTICATED'> = code;
          return rest.length > 0 ? 'known' : 'never';
        }
      }
    }

    expect(describeCode('UNAUTHENTICATED')).toBe('sign in');
    expect(describeCode('NOT_FOUND')).toBe('known');
    expect(describeCode(MADE_UP)).toBe('by status');
  });
});

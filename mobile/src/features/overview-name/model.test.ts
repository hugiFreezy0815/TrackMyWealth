import { ApiError } from '@/api/client';

import {
  isFirstOverviewSetup,
  isOverviewNameValidation,
  isOverviewVersionConflict,
  OVERVIEW_NAME_MAX,
  overviewNameTooLong,
  previewOverviewName,
  submittedOverviewName,
  type Portfolio,
} from './model';

describe('overview first-run and name rules (#490)', () => {
  it('previews the exact typed name and falls back without persisting the label', () => {
    expect(previewOverviewName('Family Hug', 'All portfolios')).toBe('Family Hug');
    expect(previewOverviewName('  ', 'Alle Portfolios')).toBe('Alle Portfolios');
    expect(submittedOverviewName('  Family Hug  ')).toBe('Family Hug');
    expect(submittedOverviewName('   ')).toBeNull();
    expect(overviewNameTooLong('x'.repeat(OVERVIEW_NAME_MAX))).toBe(false);
    expect(overviewNameTooLong('x'.repeat(OVERVIEW_NAME_MAX + 1))).toBe(true);
  });

  it('requires an unfinished device step and no member-added portfolio or account', () => {
    const item = (personalAssetsDefault: boolean, activeAccountCount: number) =>
      ({ personalAssetsDefault, activeAccountCount }) as Portfolio;
    expect(isFirstOverviewSetup([], false)).toBe(true);
    expect(isFirstOverviewSetup([item(true, 0)], false)).toBe(true);
    expect(isFirstOverviewSetup([item(true, 0)], true)).toBe(false);
    expect(isFirstOverviewSetup([item(false, 0)], false)).toBe(false);
    expect(isFirstOverviewSetup([item(true, 1)], false)).toBe(false);
  });

  it('identifies a name validation error only when the server marks name', () => {
    const invalid = new ApiError('invalid', 400, {
      code: 'VALIDATION_FAILED', title: 'Bad request', status: 400,
      errors: [{ field: 'name', message: 'Not allowed' }],
    });
    const unrelated = new ApiError('invalid', 400, {
      code: 'VALIDATION_FAILED', title: 'Bad request', status: 400,
      errors: [{ field: 'currency', message: 'Invalid' }],
    });
    expect(isOverviewNameValidation(invalid)).toBe(true);
    expect(isOverviewNameValidation(unrelated)).toBe(false);
    expect(isOverviewNameValidation(new Error('failed'))).toBe(false);
  });

  it('recognises stale versions, but not arbitrary failures', () => {
    expect(isOverviewVersionConflict(new ApiError('stale', 412, {
      code: 'VERSION_CONFLICT', title: 'Stale', status: 412,
    }))).toBe(true);
    expect(isOverviewVersionConflict(new ApiError('stale', 412))).toBe(true);
    expect(isOverviewVersionConflict(new ApiError('offline', 500))).toBe(false);
  });
});

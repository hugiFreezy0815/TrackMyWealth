import { ApiError } from '@/api/client';
import { problemOf, problemReaction } from '@/api/problems';
import type { components } from '@/api/schema';

export type Workspace = components['schemas']['WorkspaceResponse'];
export type Portfolio = components['schemas']['PortfolioListItemResponse'];

export const OVERVIEW_NAME_MAX = 60;

/** The server trims; keeping the draft intact lets a member edit before saving. */
export function submittedOverviewName(draft: string): string | null {
  return draft.trim() || null;
}

/**
 * The length the server checks (`WorkspaceNames.isValid`): surrounding spaces removed first, then
 * counted in code points, so an emoji is one character, not the two UTF-16 units of `.length`.
 */
export function overviewNameLength(draft: string): number {
  return Array.from(draft.trim()).length;
}

export function overviewNameTooLong(draft: string): boolean {
  return overviewNameLength(draft) > OVERVIEW_NAME_MAX;
}

export function previewOverviewName(draft: string, fallback: string): string {
  return draft.trim() || fallback;
}

/** Only the automatic, empty Personal assets portfolio can exist before the first setup. */
export function isFirstOverviewSetup(
  portfolios: readonly Portfolio[],
  completedOnDevice: boolean,
): boolean {
  return (
    !completedOnDevice &&
    portfolios.every(
      (portfolio) => portfolio.personalAssetsDefault && portfolio.activeAccountCount === 0,
    )
  );
}

export function isOverviewNameValidation(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false;
  const problem = problemOf(error);
  return (
    problem?.code === 'VALIDATION_FAILED' &&
    problem.errors?.some((item) => item.field === 'name') === true
  );
}

/**
 * Only a stale `If-Match` opens the "changed meanwhile" dialog: the `VERSION_CONFLICT` code, or a
 * bare 412, which a request with `If-Match` gets for nothing else. Another 409 code is not a rename
 * by someone else and keeps its own reaction.
 */
export function isOverviewVersionConflict(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false;
  const { sentCode, status } = problemReaction(error);
  return sentCode === 'VERSION_CONFLICT' || (sentCode === undefined && status === 412);
}

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

export function overviewNameTooLong(draft: string): boolean {
  // Java's @ValidWorkspaceName checks UTF-16 string length, the same as JS .length.
  return draft.length > OVERVIEW_NAME_MAX;
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
    portfolios.every((portfolio) => portfolio.personalAssetsDefault && portfolio.activeAccountCount === 0)
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

export function isOverviewVersionConflict(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false;
  const reaction = problemReaction(error);
  return reaction.sentCode === 'VERSION_CONFLICT' || (reaction.reaction === 'RELOAD' && (error.status === 409 || error.status === 412));
}

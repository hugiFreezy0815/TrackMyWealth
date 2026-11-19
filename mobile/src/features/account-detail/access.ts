import { ApiError } from '@/api/client';
import { problemReaction } from '@/api/problems';

export const isNotFound = (error: unknown) =>
  error instanceof ApiError &&
  (error.status === 404 || problemReaction(error).sentCode === 'NOT_FOUND');

/** An account error must not reveal a previously cached financial value after access was denied. */
export const isAccessFailure = (error: unknown) =>
  error instanceof ApiError &&
  (error.status === 401 ||
    error.status === 403 ||
    isNotFound(error) ||
    problemReaction(error).sentCode === 'UNAUTHENTICATED' ||
    problemReaction(error).sentCode === 'FORBIDDEN');

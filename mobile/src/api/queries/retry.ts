import { ApiError, NetworkError } from '@/api/client';
import { problemReaction } from '@/api/problems';

export function retryDelay(attempt: number, error: unknown): number {
  if (error instanceof ApiError && typeof error.retryAfterSeconds === 'number') {
    return Math.max(0, Math.min(60_000, error.retryAfterSeconds * 1000));
  }
  return Math.min(1000 * 2 ** attempt, 30_000);
}

export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (failureCount >= 3) return false;
  if (error instanceof NetworkError) return true;
  if (!(error instanceof ApiError)) return false;
  const reaction = problemReaction(error).reaction;
  return reaction === 'RETRY' || reaction === 'RETRY_LATER';
}

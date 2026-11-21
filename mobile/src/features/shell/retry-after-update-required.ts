import { ApiError } from '@/api/client';
import { typedApi } from '@/api/typed-api';
import { queryClient } from '@/api/queries/client';
import { dismissUpdateRequired } from '@/api/update-required';
import { resumeSessionAfterUpdate } from '@/features/auth/session';

/**
 * Keep the full-screen update barrier until a version-bearing API request proves the server
 * accepts this build. /users/me is safe and exists for both signed-in and signed-out members;
 * 401/403 mean the version filter let the request through to authorization.
 *
 * A 426, network failure or unexpected status leaves the barrier and stored session untouched.
 * Avoid refreshing credentials during this probe: the member may have a retained but expired
 * access token, and refresh is only meaningful once the version gate is lifted.
 */
export async function retryAfterUpdateRequired(): Promise<void> {
  try {
    await typedApi.get('/api/v1/users/me', { renewSession: false });
  } catch (error) {
    if (!(error instanceof ApiError && (error.status === 401 || error.status === 403))) {
      throw error;
    }
  }

  // A saved session may have failed to refresh or verify its identity while blocked by 426. If the
  // server now rejects that refresh token, the session ends here and the app opens on sign-in.
  await resumeSessionAfterUpdate();
  // Back to the app first, then mark cached data stale without waiting for the refetches: a slow
  // query must not keep Retry spinning, and a 426 during a refetch raises the barrier again.
  dismissUpdateRequired();
  void queryClient.invalidateQueries();
}

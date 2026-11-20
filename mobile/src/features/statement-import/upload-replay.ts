/**
 * Whether the server replays a keyed upload (#574): a retry with the same Idempotency-Key answers
 * the first upload's 201 and creates no second batch. That makes a retry after a lost response
 * safe, so the app offers one. The backend ships it with this app version
 * (`ImportBatchIdempotencyTest`); this is a build-time capability, not a runtime probe of an older
 * self-hosted server.
 */
export function supportsUploadReplay(): boolean {
  return true;
}

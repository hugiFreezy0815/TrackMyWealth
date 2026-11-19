/**
 * #574 backend replay is not yet available. A failed response does not prove that the server
 * did not create a batch, so resending an uncertain upload would create duplicates.
 *
 * Do not enable this until the upload endpoint actually stores/replays the keyed response and
 * its lost-response integration test passes. This is a build-time capability, not a runtime
 * probe of an older self-hosted server.
 */
export function supportsUploadReplay(): boolean {
  return false;
}

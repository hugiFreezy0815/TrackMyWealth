import { randomUUID } from 'expo-crypto';
import { useState } from 'react';

/**
 * ADR 0006 (#629): one Idempotency-Key per logical create. Sending the same request again (a
 * retry after a lost response, a timeout or an error) reuses its key, so the server answers the
 * first attempt's result instead of creating a second record. A different request, or any request
 * after the create succeeded, gets a fresh key.
 */
export type IdempotencyKeys = {
  /** The key for `request`: the pending one when it is the same request, else a new one. */
  keyFor(request: unknown): string;
  /** The create succeeded: the next request is a new create, even with the same values. */
  succeeded(): void;
};

export function idempotencyKeys(): IdempotencyKeys {
  let pending: { request: string; key: string } | null = null;
  return {
    keyFor(request) {
      const fingerprint = JSON.stringify(request);
      if (pending?.request !== fingerprint) {
        pending = { request: fingerprint, key: randomUUID() };
      }
      return pending.key;
    },
    succeeded() {
      pending = null;
    },
  };
}

/** The keys of one create form, kept for as long as the component that sends it. */
export function useIdempotencyKeys(): IdempotencyKeys {
  const [keys] = useState(idempotencyKeys);
  return keys;
}

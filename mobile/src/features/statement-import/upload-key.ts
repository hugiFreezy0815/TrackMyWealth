import { randomUUID } from 'expo-crypto';

/** A fresh key for each logical upload. A retry must use its original key. */
export function keyForUploadAttempt(previous: string | null, retrying: boolean): string {
  return retrying && previous ? previous : randomUUID();
}

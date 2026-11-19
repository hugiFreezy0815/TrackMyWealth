import { randomUUID } from 'expo-crypto';

import { keyForUploadAttempt } from './upload-key';

jest.mock('expo-crypto', () => ({ randomUUID: jest.fn() }));

beforeEach(() => jest.mocked(randomUUID).mockReset());

it('reuses the UUID only when retrying the same logical upload', () => {
  const uuid = jest.mocked(randomUUID);
  uuid.mockReturnValueOnce('first-uuid').mockReturnValueOnce('second-uuid');

  const first = keyForUploadAttempt(null, false);
  expect(first).toBe('first-uuid');
  expect(keyForUploadAttempt(first, true)).toBe(first);
  expect(keyForUploadAttempt(first, false)).toBe('second-uuid');
  expect(uuid).toHaveBeenCalledTimes(2);
});

it('creates a new key when no retry key remains', () => {
  jest.mocked(randomUUID).mockReturnValueOnce('recovered-uuid');
  expect(keyForUploadAttempt(null, true)).toBe('recovered-uuid');
});

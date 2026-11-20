import { idempotencyKeys } from './idempotency-key';

let mockUuids = 0;
jest.mock('expo-crypto', () => ({ randomUUID: jest.fn(() => `key-${++mockUuids}`) }));

describe('idempotencyKeys (#629)', () => {
  beforeEach(() => {
    mockUuids = 0;
  });

  it('keeps the key while the same request is sent again', () => {
    const keys = idempotencyKeys();
    const first = keys.keyFor({ name: 'Giro', amount: '1.00' });

    expect(keys.keyFor({ name: 'Giro', amount: '1.00' })).toBe(first);
  });

  it('gives a changed request a new key', () => {
    const keys = idempotencyKeys();
    const first = keys.keyFor({ name: 'Giro' });

    expect(keys.keyFor({ name: 'Savings' })).not.toBe(first);
  });

  it('gives the next create a new key once the last one succeeded', () => {
    const keys = idempotencyKeys();
    const first = keys.keyFor({ name: 'Giro' });
    keys.succeeded();

    expect(keys.keyFor({ name: 'Giro' })).not.toBe(first);
  });
});

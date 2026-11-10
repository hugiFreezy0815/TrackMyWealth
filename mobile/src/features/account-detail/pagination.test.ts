import { nextPageTrigger } from './pagination';

describe('last-ten-row transaction pagination', () => {
  const firstPage = Array.from({ length: 50 }, (_, index) => `txn-${index + 1}`);

  it('waits until rows 41–50 become visible, independent of viewport size', () => {
    expect(nextPageTrigger(firstPage, ['txn-39', 'txn-40'], null)).toBeNull();
    expect(nextPageTrigger(firstPage, ['txn-41'], null)).toBe('txn-50');
    expect(nextPageTrigger(firstPage, ['txn-50'], null)).toBe('txn-50');
  });

  it('requests each loaded tail at most once, then allows the next page', () => {
    expect(nextPageTrigger(firstPage, ['txn-41'], 'txn-50')).toBeNull();
    const secondPage = [...firstPage, ...Array.from({ length: 50 }, (_, index) => `txn-${index + 51}`)];
    expect(nextPageTrigger(secondPage, ['txn-90'], 'txn-50')).toBeNull();
    expect(nextPageTrigger(secondPage, ['txn-91'], 'txn-50')).toBe('txn-100');
    expect(nextPageTrigger(secondPage, ['txn-92'], 'txn-100')).toBeNull();
  });

  it('handles a partial last page and empty lists', () => {
    expect(nextPageTrigger([], [], null)).toBeNull();
    expect(nextPageTrigger(['a', 'b'], ['a'], null)).toBe('b');
    expect(nextPageTrigger(['a', 'b'], ['b'], 'b')).toBeNull();
  });
});

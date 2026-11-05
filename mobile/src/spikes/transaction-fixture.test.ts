import { createTransactions, selectTransactions, toCsv } from './transaction-fixture';

describe('issue 384 synthetic transactions', () => {
  it('builds exact, repeatable row counts without customer data', () => {
    const rows = createTransactions(10_000);
    expect(rows).toHaveLength(10_000);
    expect(rows[0]).toEqual(createTransactions(1)[0]);
    expect(new Set(rows.map((r) => r.id)).size).toBe(10_000);
    expect(() => createTransactions(10_001)).toThrow(RangeError);
  });

  it('sorts tied dates deterministically and filters across 10,000 rows', () => {
    const rows = createTransactions(10_000);
    const page = selectTransactions(rows, '', '', 'bookingDate', 'desc');
    expect(page).toHaveLength(10_000);
    expect(page.slice(0, 4).map((r) => r.id)).toEqual([
      'demo-00004',
      'demo-00003',
      'demo-00002',
      'demo-00001',
    ]);
    const selected = selectTransactions(rows, 'Demo railway', 'Transport', 'amount', 'asc');
    expect(selected.length).toBeGreaterThan(1_000);
    expect(selected.every((r) => r.category === 'Transport')).toBe(true);
    expect(selected[0].amount).toBeLessThanOrEqual(selected.at(-1)!.amount);
  });

  it('exports only the filtered dataset with escaped CSV values', () => {
    const selected = selectTransactions(createTransactions(1_000), 'Sample shop', '', 'bookingDate', 'desc');
    const csv = toCsv(selected);
    expect(csv.split('\r\n')).toHaveLength(selected.length + 2);
    expect(csv).toContain('"Sample shop #00001"');
    expect(csv).not.toContain('Demo railway');
  });
});

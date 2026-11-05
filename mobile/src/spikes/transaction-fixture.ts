/**
 * Deterministic, invented transactions for the #384 web-client comparison.
 * Never use a real bank export, production credentials, or personally identifying information.
 */
export type DemoTransaction = {
  id: string;
  bookingDate: string;
  description: string;
  account: string;
  category: string;
  amount: number;
  currency: 'CHF';
};

export type SortField = 'bookingDate' | 'description' | 'account' | 'category' | 'amount';
export type SortDirection = 'asc' | 'desc';

const categories = ['Groceries', 'Transport', 'Housing', 'Salary', 'Other'] as const;
const merchants = [
  'Sample shop',
  'Demo railway',
  'Example landlord',
  'Test payroll',
  'Illustrative café',
] as const;
const accounts = ['Demo current account', 'Demo card', 'Demo savings'] as const;

export function createTransactions(count: number): DemoTransaction[] {
  if (!Number.isInteger(count) || count < 0 || count > 10_000) {
    throw new RangeError('The benchmark must use between 0 and 10,000 synthetic rows.');
  }
  const firstDay = Date.UTC(2026, 9, 31);
  return Array.from({ length: count }, (_, i) => {
    const categoryIndex = i % categories.length;
    return {
      id: `demo-${String(i + 1).padStart(5, '0')}`,
      bookingDate: new Date(firstDay - Math.floor(i / 4) * 86_400_000).toISOString().slice(0, 10),
      description: `${merchants[categoryIndex]} #${String(i + 1).padStart(5, '0')}`,
      account: accounts[i % accounts.length],
      category: categories[categoryIndex],
      amount: categoryIndex === 3 ? 1500 + (i % 50) : -(1 + ((i * 17) % 3000)) / 10,
      currency: 'CHF',
    };
  });
}

export function selectTransactions(
  rows: readonly DemoTransaction[],
  search: string,
  category: string,
  field: SortField,
  direction: SortDirection,
): DemoTransaction[] {
  const needle = search.trim().toLocaleLowerCase('en');
  const filtered = rows.filter(
    (row) =>
      (!category || row.category === category) &&
      (!needle ||
        row.description.toLocaleLowerCase('en').includes(needle) ||
        row.account.toLocaleLowerCase('en').includes(needle) ||
        row.id.toLocaleLowerCase('en').includes(needle)),
  );
  const order = direction === 'asc' ? 1 : -1;
  return filtered.sort((a, b) => {
    const result =
      field === 'amount'
        ? a.amount - b.amount
        : String(a[field]).localeCompare(String(b[field]), 'en');
    // Stable deterministic tie-break independent of JS engine and input order.
    return (result !== 0 ? result : a.id.localeCompare(b.id, 'en')) * order;
  });
}

function csvCell(text: string): string {
  // Escape CSV metacharacters and neutralise formula-like values in spreadsheet apps.
  const safe = /^[=+@\t\r]/.test(text) ? `'${text}` : text;
  return `"${safe.replaceAll('"', '""')}"`;
}

export function toCsv(rows: readonly DemoTransaction[]): string {
  const header = ['Date', 'Description', 'Account', 'Category', 'Amount', 'Currency'];
  const lines = rows.map((r) =>
    [r.bookingDate, r.description, r.account, r.category, r.amount.toFixed(2), r.currency]
      .map(csvCell)
      .join(','),
  );
  return [header.join(','), ...lines].join('\r\n') + '\r\n';
}

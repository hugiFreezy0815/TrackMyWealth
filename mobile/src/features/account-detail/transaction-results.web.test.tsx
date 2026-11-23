import type { ReactNode } from 'react';
import { IntlProvider } from 'react-intl';
import { fireEvent, screen } from '@testing-library/react-native';

import en from '@/i18n/en.json';
import { renderWithIntl } from '@/i18n/test-utils';
import type { RowContext, Transaction } from './model';
import { TransactionResults as NativeResults } from './transaction-results.native';
import { sameRow, TransactionResults as WebResults } from './transaction-results.web';

jest.mock('@/global.css', () => ({}));
jest.mock('@/features/privacy/amount-visibility', () => ({ useAmountsHidden: () => false }));
const mockWindow = { width: 390, height: 844, scale: 1, fontScale: 1 };
jest.mock('react-native/Libraries/Utilities/useWindowDimensions', () => ({
  __esModule: true,
  default: () => mockWindow,
}));

const row = (index: number, other: Partial<Transaction> = {}) =>
  ({
    id: `t${index}`,
    bookingDate: '2026-11-09',
    transactionType: 'EXPENSE',
    amount: '-10.00',
    currency: 'EUR',
    merchantDescription: `Shop ${index}`,
    counterpartyName: null,
    categoryId: null,
    internalTransfer: false,
    voidedAt: null,
    replacesTransactionId: null,
    correctsTransactionId: null,
    reconciliationAdjustment: null,
    amountInAccountCurrency: null,
    fxRateEstimated: false,
    warnings: [],
    version: 0,
    ...other,
  }) as unknown as Transaction;
const rows = Array.from(
  { length: 50 },
  (_, index) =>
    ({
      id: `t${index}`,
      bookingDate: '2026-11-09',
      transactionType: 'EXPENSE',
      amount: '-10.00',
      currency: 'EUR',
      merchantDescription: `Shop ${index}`,
      counterpartyName: null,
      categoryId: null,
      internalTransfer: false,
      voidedAt: null,
      replacesTransactionId: null,
      correctsTransactionId: null,
      reconciliationAdjustment: null,
      amountInAccountCurrency: null,
      fxRateEstimated: false,
      warnings: [],
      version: 0,
    }) as unknown as Transaction,
);
const context: RowContext = {
  categories: [],
  reversalDates: new Map(rows.map((item) => [item.id, item.bookingDate])),
  accountCurrency: 'EUR',
  archived: false,
  includeParent: false,
};

// On web the list sits in the page's scroll view, so every row reports as viewable at once.
async function showEveryRow(Results: typeof WebResults) {
  const onEndReached = jest.fn();
  await renderWithIntl(
    <Results
      transactions={rows}
      context={context}
      byDate
      onCategory={jest.fn()}
      onEndReached={onEndReached}
    />,
  );
  // One month section: flat index 0 is its header, rows start at 1.
  const viewableItems = rows.map((item, index) => ({
    isViewable: true,
    item,
    key: item.id,
    index: index + 1,
  }));
  await fireEvent(screen.getByTestId('account-transaction-list'), 'onViewableItemsChanged', {
    viewableItems,
    changed: viewableItems,
  });
  return onEndReached;
}

describe('S3 paging on web at phone width (#494)', () => {
  it('leaves paging to the Load more button', async () => {
    expect(await showEveryRow(WebResults)).not.toHaveBeenCalled();
  });

  it('keeps infinite scroll in the native list', async () => {
    expect(await showEveryRow(NativeResults)).toHaveBeenCalledTimes(1);
  });
});

it('presents month headings, direction and overflow in the desktop table', () => {
  const { renderToStaticMarkup } = jest.requireActual<{
    renderToStaticMarkup: (node: ReactNode) => string;
  }>('react-dom/server');
  const purpose = 'Synthetic reference from the bank';
  const recent = row(0, { counterpartyName: 'Stationery', merchantDescription: purpose });
  const older = row(1, { bookingDate: '2026-10-30', amount: '10.00' });
  mockWindow.width = 1280;
  try {
    const html = renderToStaticMarkup(
      <IntlProvider locale="en" messages={en}>
        <WebResults
          transactions={[recent, older]}
          context={context}
          byDate
          onCategory={jest.fn()}
          onActions={jest.fn()}
        />
      </IntlProvider>,
    );
    expect(html).toMatch(/November 2026.*Stationery.*October 2026.*Shop 1/);
    expect(html).toContain('-webkit-line-clamp:2');
    expect(html).toContain(purpose);
    expect(html).toContain('Outgoing');
    expect(html).toContain('Incoming');
    expect(html).toContain('aria-label="Actions for Stationery"');
  } finally {
    mockWindow.width = 390;
  }
});

describe('S3 web table growth with Load more (#494)', () => {
  const open = jest.fn();
  const pick = jest.fn();
  const props = (transaction: Transaction, loaded: Transaction[], other = {}) => ({
    transaction,
    context: {
      ...context,
      reversalDates: new Map(loaded.map((item) => [item.id, item.bookingDate])),
    },
    selected: false,
    byDate: true,
    today: '2026-11-09',
    onOpen: open,
    onCategory: pick,
    ...other,
  });
  const page0 = Array.from({ length: 50 }, (_, index) => row(index));
  const page1 = [...page0, ...Array.from({ length: 50 }, (_, index) => row(50 + index))];

  it('skips a loaded row when a page is appended', () => {
    expect(sameRow(props(page0[3], page0), props(page0[3], page1))).toBe(true);
  });

  it("re-renders a reversal row when the original's date becomes available", () => {
    const reversal = row(0, { replacesTransactionId: 't60' });
    const withOriginal = [...page0, row(60, { bookingDate: '2026-10-01' })];
    expect(sameRow(props(reversal, page0), props(reversal, withOriginal))).toBe(false);
    expect(sameRow(props(reversal, page0), props(reversal, page1.slice(0, 55)))).toBe(true);
  });

  it('re-renders a row whose data, selection or categories changed', () => {
    const before = props(page0[3], page0);
    expect(sameRow(before, props(row(3, { categoryId: 'food' }), page0))).toBe(false);
    expect(sameRow(before, props(page0[3], page0, { selected: true }))).toBe(false);
    expect(sameRow(before, { ...before, context: { ...before.context, categories: [] } })).toBe(
      false,
    );
    expect(sameRow(before, { ...before, context: { ...before.context, archived: true } })).toBe(
      false,
    );
  });

  it('re-renders a row at midnight so "Today" becomes "Yesterday"', () => {
    const before = props(page0[3], page0);
    expect(sameRow(before, { ...before, today: '2026-11-10' })).toBe(false);
  });
});

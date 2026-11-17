import { fireEvent, screen } from '@testing-library/react-native';

import { renderWithIntl } from '@/i18n/test-utils';
import type { RowContext, Transaction } from './model';
import { TransactionResults as NativeResults } from './transaction-results.native';
import { TransactionResults as WebResults } from './transaction-results.web';

jest.mock('@/global.css', () => ({}));
const mockWindow = { width: 390, height: 844, scale: 1, fontScale: 1 };
jest.mock('react-native/Libraries/Utilities/useWindowDimensions', () => ({
  __esModule: true,
  default: () => mockWindow,
}));

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
  loaded: rows,
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
  // One date section: flat index 0 is its header, rows start at 1.
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

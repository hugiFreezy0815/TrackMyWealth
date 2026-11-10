import { useMemo, useRef, type ReactNode } from 'react';
import { SectionList, Text, View, type ViewToken } from 'react-native';
import { useIntl } from 'react-intl';

import { TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

import { groupHeading, groupedTransactions, type RowContext, type Transaction } from './model';
import { TransactionLine } from './transaction-line';

export type TransactionResultsProps = {
  transactions: Transaction[];
  context: RowContext;
  byDate: boolean;
  onOpen?: (id: string) => void;
  onCategory: (transaction: Transaction) => void;
  selectedTransactionId?: string | null;
  onEndReached?: () => void;
  /** The account controls are part of the same virtualized scroll content, not a parent ScrollView. */
  listHeader?: ReactNode;
  listFooter?: ReactNode;
  refreshing?: boolean;
  onRefresh?: () => void;
};

/** Native transactions use a single virtualized SectionList even after 1,000+ rows. */
export function TransactionResults({
  transactions,
  context,
  byDate,
  onOpen,
  onCategory,
  onEndReached,
  listHeader,
  listFooter,
  refreshing = false,
  onRefresh,
}: TransactionResultsProps) {
  const intl = useIntl();
  const theme = useTheme();
  const loadMore = useRef(onEndReached);
  loadMore.current = onEndReached;
  const lastTriggered = useRef<string | null>(null);
  const lastTen = useMemo(
    () => new Set(transactions.slice(-10).map((item) => item.id)),
    [transactions],
  );
  const lastTenRef = useRef(lastTen);
  lastTenRef.current = lastTen;
  const viewabilityConfig = useRef({ itemVisiblePercentThreshold: 1 }).current;
  const onViewableItemsChanged = useRef(
    ({ viewableItems }: { viewableItems: ViewToken[] }) => {
      const trigger = loadMore.current;
      if (!trigger || !viewableItems.some((token) => token.isViewable && token.item && lastTenRef.current.has((token.item as Transaction).id))) return;
      // One automatic request per loaded tail; the next page produces a new tail.
      const tail = [...lastTenRef.current].slice(-1)[0];
      if (!tail || lastTriggered.current === tail) return;
      lastTriggered.current = tail;
      trigger();
    },
  ).current;
  const sections = useMemo(
    () =>
      groupedTransactions(transactions, byDate).map((group, index) => ({
        key: group.date ?? `unsorted-${index}`,
        date: group.date,
        data: group.rows,
      })),
    [transactions, byDate],
  );
  return (
    <SectionList
      testID="account-transaction-list"
      style={{ flex: 1 }}
      contentContainerStyle={{ paddingBottom: 24 }}
      sections={sections}
      keyExtractor={(item) => item.id}
      ListHeaderComponent={listHeader ? <View style={{ gap: 12 }}>{listHeader}</View> : undefined}
      ListFooterComponent={listFooter ? <View style={{ gap: 12 }}>{listFooter}</View> : undefined}
      renderSectionHeader={({ section }) =>
        section.date ? (
          <Text
            accessibilityRole="header"
            style={{ ...TypeScale.labelMedium, color: theme.textSecondary }}
          >
            {groupHeading(section.date, intl)}
          </Text>
        ) : null
      }
      renderItem={({ item }) => (
        <TransactionLine
          transaction={item}
          context={context}
          showDate={!byDate}
          onOpen={onOpen}
          onCategory={onCategory}
        />
      )}
      onViewableItemsChanged={onViewableItemsChanged}
      viewabilityConfig={viewabilityConfig}
      initialNumToRender={12}
      maxToRenderPerBatch={12}
      windowSize={7}
      stickySectionHeadersEnabled={false}
      refreshing={refreshing}
      onRefresh={onRefresh}
      keyboardShouldPersistTaps="handled"
    />
  );
}

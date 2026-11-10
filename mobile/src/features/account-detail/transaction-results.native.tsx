import { useMemo, useRef, type ReactNode } from 'react';
import { SectionList, Text, View } from 'react-native';
import { useIntl } from 'react-intl';

import { TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

import { groupHeading, groupedTransactions, type RowContext, type Transaction } from './model';
import { nextPageTrigger } from './pagination';
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
  const ids = useMemo(() => transactions.map((item) => item.id), [transactions]);
  const idsRef = useRef(ids);
  idsRef.current = ids;
  const viewabilityConfig = useRef({ itemVisiblePercentThreshold: 1 }).current;
  const onViewableItemsChanged = useRef(
    ({ viewableItems }: { viewableItems: { isViewable: boolean; item: unknown }[] }) => {
      if (!loadMore.current) return;
      const visibleIds = viewableItems
        .filter((token) => token.isViewable && token.item)
        .map((token) => (token.item as Transaction).id);
      const tail = nextPageTrigger(idsRef.current, visibleIds, lastTriggered.current);
      if (!tail) return;
      lastTriggered.current = tail;
      loadMore.current();
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

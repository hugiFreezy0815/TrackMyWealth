import { useRef, type ComponentRef } from 'react';
import { Text, View } from 'react-native';
import { useIntl } from 'react-intl';

import { TypeScale } from '@/constants/theme';
import { useWhenOnScreen } from '@/features/shell/scroll-events';
import { useTheme } from '@/hooks/use-theme';

import { groupHeading, groupedTransactions, type RowContext, type Transaction } from './model';
import { TransactionLine } from './transaction-line';

export type TransactionResultsProps = {
  transactions: Transaction[];
  context: RowContext;
  byDate: boolean;
  onOpen: (id: string) => void;
  onCategory: (transaction: Transaction) => void;
  selectedTransactionId?: string | null;
  /** Phone infinite scroll: the next page loads once the last 10 rows come into view. */
  onEndReached?: () => void;
};

/** How many rows before the end the next page starts loading (spec S3 "Paging"). */
export const END_REACHED_ROWS = 10;

function EndMarker({ onVisible }: { onVisible: () => void }) {
  const ref = useRef<ComponentRef<typeof View>>(null);
  const check = useWhenOnScreen(ref, onVisible, true);
  return <View ref={ref} testID="transactions-end-marker" onLayout={check} />;
}

export function TransactionResults({
  transactions,
  context,
  byDate,
  onOpen,
  onCategory,
  onEndReached,
}: TransactionResultsProps) {
  const intl = useIntl();
  const theme = useTheme();
  const markerId = onEndReached
    ? transactions[Math.max(0, transactions.length - END_REACHED_ROWS)]?.id
    : undefined;
  return (
    <View>
      {groupedTransactions(transactions, byDate).map((group, index) => (
        <View key={group.date ?? `amount-${index}`}>
          {group.date ? (
            <Text
              accessibilityRole="header"
              style={{ ...TypeScale.labelMedium, color: theme.textSecondary }}
            >
              {groupHeading(group.date, intl)}
            </Text>
          ) : null}
          {group.rows.map((row) => (
            <View key={row.id}>
              {row.id === markerId && onEndReached ? <EndMarker onVisible={onEndReached} /> : null}
              <TransactionLine
                transaction={row}
                context={context}
                showDate={!byDate}
                onOpen={onOpen}
                onCategory={onCategory}
              />
            </View>
          ))}
        </View>
      ))}
    </View>
  );
}

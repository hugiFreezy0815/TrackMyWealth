import { Text, View } from 'react-native';
import { useIntl } from 'react-intl';

import { TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

import { groupHeading, groupedTransactions, type RowContext, type Transaction } from './model';
import { TransactionLine } from './transaction-line';

export type TransactionResultsProps = {
  transactions: Transaction[];
  context: RowContext;
  byDate: boolean;
  onOpen: (id: string) => void;
  onCategory: (transaction: Transaction) => void;
};

export function TransactionResults({
  transactions,
  context,
  byDate,
  onOpen,
  onCategory,
}: TransactionResultsProps) {
  const intl = useIntl();
  const theme = useTheme();
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
            <TransactionLine
              key={row.id}
              transaction={row}
              context={context}
              showDate={!byDate}
              onOpen={onOpen}
              onCategory={onCategory}
            />
          ))}
        </View>
      ))}
    </View>
  );
}

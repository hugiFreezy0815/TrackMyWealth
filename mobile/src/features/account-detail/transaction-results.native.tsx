import { Text, View } from 'react-native';
import { useIntl } from 'react-intl';
import { TypeScale } from '@/constants/theme';
import { formatDate, formatRelativeDay } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';
import { groupedTransactions, type Category, type Transaction } from './model';
import { TransactionLine } from './transaction-line';

export type TransactionResultsProps = {
  transactions: Transaction[];
  categories: Category[];
  archived: boolean;
  byDate: boolean;
  onOpen: (id: string) => void;
  onCategory: (transaction: Transaction) => void;
};

export function TransactionResults({
  transactions,
  categories,
  archived,
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
              {formatRelativeDay(group.date, intl)} · {formatDate(group.date, intl.locale)}
            </Text>
          ) : null}
          {group.rows.map((row) => (
            <TransactionLine
              key={row.id}
              transaction={row}
              categories={categories}
              archived={archived}
              onOpen={onOpen}
              onCategory={onCategory}
            />
          ))}
        </View>
      ))}
    </View>
  );
}

import { useIntl } from 'react-intl';
import { Pressable, ScrollView, Text, useWindowDimensions } from 'react-native';

import type { TransactionSort } from '@/api/queries/resources';
import { ActionButton, BottomSheet, Dialog, valueStatusTexts } from '@/components/shared';
import { formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';
import { dataWarningMessageIds } from '@/features/overview/overview-model';

import {
  asOfLine,
  categoryName,
  valueSource,
  valueSourceLabel,
  type AccountListItem,
  type AccountSummary,
  type Category,
} from './model';

export const SORTS: readonly { value: TransactionSort; messageId: string }[] = [
  { value: 'bookingDate,desc', messageId: 'account.newest' },
  { value: 'bookingDate,asc', messageId: 'account.oldest' },
  { value: 'amount,desc', messageId: 'account.largest' },
  { value: 'amount,asc', messageId: 'account.smallest' },
];

export const sortMessageId = (sort: TransactionSort) =>
  SORTS.find((choice) => choice.value === sort)?.messageId ?? 'account.newest';

/** ⋯ menu. Importing changes the account, so it is off while the account is archived. */
export function ActionsDialog({
  visible,
  archived,
  online,
  onClose,
  onImport,
  onArchive,
}: {
  visible: boolean;
  archived: boolean;
  online: boolean;
  onClose: () => void;
  onImport: () => void;
  onArchive: () => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  return (
    <Dialog
      visible={visible}
      title={intl.formatMessage({ id: 'account.actions' })}
      onClose={onClose}
    >
      <ActionButton
        label={intl.formatMessage({ id: 'account.import' })}
        disabled={archived}
        onPress={onImport}
      />
      {archived ? (
        <Text style={{ color: theme.textSecondary }}>
          {intl.formatMessage({ id: 'account.archivedReason' })}
        </Text>
      ) : (
        <ActionButton
          label={intl.formatMessage({ id: 'account.archive' })}
          disabled={!online}
          onPress={onArchive}
        />
      )}
    </Dialog>
  );
}

export function SortDialog({
  visible,
  sort,
  onClose,
  onSort,
}: {
  visible: boolean;
  sort: TransactionSort;
  onClose: () => void;
  onSort: (sort: TransactionSort) => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  return (
    <Dialog visible={visible} title={intl.formatMessage({ id: 'account.sort' })} onClose={onClose}>
      {SORTS.map((choice) => (
        <Pressable
          key={choice.value}
          accessibilityRole="radio"
          aria-checked={sort === choice.value}
          onPress={() => onSort(choice.value)}
          style={{ minHeight: 44, justifyContent: 'center' }}
        >
          <Text style={{ color: theme.textPrimary }}>
            {sort === choice.value ? '✓ ' : ''}
            {intl.formatMessage({ id: choice.messageId })}
          </Text>
        </Pressable>
      ))}
    </Dialog>
  );
}

export function CategoryPickerDialog({
  visible,
  categories,
  disabled,
  onClose,
  onPick,
}: {
  visible: boolean;
  categories: Category[];
  disabled: boolean;
  onClose: () => void;
  onPick: (categoryId: string) => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const german = intl.locale.startsWith('de');
  const { height } = useWindowDimensions();
  return (
    <Dialog
      visible={visible}
      title={intl.formatMessage({ id: 'account.changeCategory' })}
      onClose={onClose}
    >
      {/* About 30 categories: the list scrolls so the title and Close stay on screen. */}
      <ScrollView testID="category-picker-list" style={{ maxHeight: Math.round(height * 0.6) }}>
        {categories
          .filter((category) => category.active && category.code !== 'UNCATEGORIZED')
          .map((category) => (
            <Pressable
              key={category.id}
              accessibilityRole="button"
              disabled={disabled}
              onPress={() => onPick(category.id)}
              style={{ minHeight: 44, justifyContent: 'center' }}
            >
              <Text style={{ color: theme.textPrimary }}>
                {categoryName(category, categories, german, true)}
              </Text>
            </Pressable>
          ))}
      </ScrollView>
    </Dialog>
  );
}

export function ArchiveDialog({
  visible,
  name,
  disabled,
  onClose,
  onConfirm,
}: {
  visible: boolean;
  name: string;
  disabled: boolean;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  return (
    <Dialog
      visible={visible}
      title={intl.formatMessage({ id: 'account.archive' })}
      onClose={onClose}
    >
      <Text style={{ color: theme.textPrimary }}>
        {intl.formatMessage({ id: 'account.archiveConfirm' }, { name })}
      </Text>
      <ActionButton
        disabled={disabled}
        label={intl.formatMessage({ id: 'account.archive' })}
        onPress={onConfirm}
      />
    </Dialog>
  );
}

/** Tap on the value: its basis, dates, the status explanation and the warnings (loaded data). */
export function ValueExplanationSheet({
  visible,
  summary,
  value,
  onClose,
}: {
  visible: boolean;
  summary: AccountSummary;
  value: AccountListItem | undefined;
  onClose: () => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  if (!value) return null;
  const status = valueStatusTexts(value.valueStatus, value.valueSourceDate, intl);
  const lines = [
    intl.formatMessage(
      { id: 'account.valueFrom' },
      { basis: valueSourceLabel(valueSource(summary), intl) },
    ),
    asOfLine(valueSource(summary), value, intl),
    value.valueSourceDate
      ? intl.formatMessage(
          { id: 'account.valueSourceDate' },
          { date: formatDate(value.valueSourceDate, intl.locale) },
        )
      : null,
    status?.label ?? null,
    status?.detail ?? null,
    ...dataWarningMessageIds(value.warnings).map((id) => intl.formatMessage({ id })),
  ].filter((line): line is string => line !== null);
  return (
    <BottomSheet
      visible={visible}
      title={intl.formatMessage({ id: 'account.explain' })}
      onClose={onClose}
    >
      {lines.map((line) => (
        <Text key={line} style={{ color: theme.textPrimary }}>
          {line}
        </Text>
      ))}
    </BottomSheet>
  );
}

import { useState } from 'react';
import type { IntlShape } from 'react-intl';
import { Pressable, Text, View } from 'react-native';

import { Dialog } from '@/components/shared';
import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import type { Account } from './overview-model';
import { msg } from './overview-text';

/**
 * S1 #5: count of unreconciled accounts + "Review". One account opens it directly (S3); more
 * open a sheet that lists them, each opening its account.
 */
export function DataStatusBanner({
  accounts,
  intl,
  onReviewAccount,
}: {
  accounts: readonly Account[];
  intl: IntlShape;
  onReviewAccount: (accountId: string) => void;
}) {
  const theme = useTheme();
  const [listOpen, setListOpen] = useState(false);
  if (accounts.length === 0) return null;
  const review = () => {
    if (accounts.length === 1) onReviewAccount(accounts[0].id);
    else setListOpen(true);
  };
  const link = { ...TypeScale.labelMedium, color: theme.brandLink };
  return (
    <View
      accessibilityRole="alert"
      style={{
        borderLeftWidth: 4,
        borderLeftColor: theme.statusWarning,
        borderRadius: Radii.control,
        backgroundColor: theme.statusWarningSubtle,
        padding: Spacing.compact,
      }}
    >
      <Text style={{ ...TypeScale.bodyMedium, color: theme.textPrimary }}>
        {msg(intl, 'overview.dataStatus.unreconciled', { count: accounts.length })}
      </Text>
      <Pressable
        accessibilityRole="button"
        onPress={review}
        style={{ minHeight: 44, justifyContent: 'center', alignSelf: 'flex-start' }}
      >
        <Text style={link}>{msg(intl, 'overview.warning.review')}</Text>
      </Pressable>
      <Dialog
        title={msg(intl, 'overview.dataStatus.listTitle')}
        visible={listOpen}
        onClose={() => setListOpen(false)}
      >
        {accounts.map((account) => (
          <Pressable
            key={account.id}
            accessibilityRole="button"
            accessibilityHint={msg(intl, 'overview.dataStatus.openAccount')}
            onPress={() => {
              setListOpen(false);
              onReviewAccount(account.id);
            }}
            style={{ minHeight: 44, justifyContent: 'center' }}
          >
            <Text style={link}>
              {msg(intl, 'overview.dataStatus.accountRow', {
                name: account.name,
                portfolio: account.portfolioName,
              })}
            </Text>
          </Pressable>
        ))}
      </Dialog>
    </View>
  );
}

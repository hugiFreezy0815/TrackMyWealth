import { useIntl } from 'react-intl';
import { View } from 'react-native';

import { useAccountPositions } from '@/api/queries/resources';
import { ActionButton, ErrorBanner, LoadingSkeleton } from '@/components/shared';
import { Spacing } from '@/constants/theme';
import { msg } from '@/features/overview/overview-text';

import { INLINE_POSITIONS, type PortfolioAccount } from './model';
import { PositionView } from './position-view';

/**
 * The top positions of one depot/crypto account (#478, server-valued, in the order sent). The
 * account row and subtotal come from the view, so a failed request only affects this list: its
 * ErrorBanner with Retry stays inside the depot card (spec S2, states).
 */
export function DepotPositions({
  account,
  onAccount,
}: {
  account: PortfolioAccount;
  onAccount: (accountId: string) => void;
}) {
  const intl = useIntl();
  const positions = useAccountPositions(account.accountId);
  const open = () => onAccount(account.accountId);
  if (positions.isPending) return <LoadingSkeleton count={2} />;
  if (positions.isError) {
    return <ErrorBanner error={positions.error} onRetry={() => void positions.refetch()} />;
  }
  const { currency, positions: all } = positions.data;
  if (all.length === 0) return null;
  return (
    <View style={{ gap: Spacing.two }}>
      <PositionView
        positions={all.slice(0, INLINE_POSITIONS)}
        currency={currency}
        accountName={account.name}
        onOpen={open}
      />
      {all.length > INLINE_POSITIONS ? (
        <ActionButton
          label={msg(intl, 'portfolio.positions.all', { count: all.length })}
          onPress={open}
        />
      ) : null}
    </View>
  );
}

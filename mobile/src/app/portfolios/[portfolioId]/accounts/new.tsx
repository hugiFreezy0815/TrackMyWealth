import { useEffect } from 'react';
import { useLocalSearchParams, useRouter, type Href } from 'expo-router';
import { useIntl } from 'react-intl';
import { View } from 'react-native';

import { ErrorBanner, LoadingSkeleton } from '@/components/shared';
import { Spacing } from '@/constants/theme';
import { AccountForm } from '@/features/add-flow/account-form';
import { isNotFound } from '@/features/add-flow/model';
import { usePortfolio } from '@/features/add-flow/use-create';
import { ShellScreen } from '@/features/shell/screen';
import { showToast } from '@/features/shell/toast';

function leaveRemovedPortfolio(router: ReturnType<typeof useRouter>) {
  showToast('addAccount.portfolioGone');
  router.replace('/overview');
}

/** S6 Add account. Closes on the overview until the Portfolio View (S2, #493) exists. */
export default function AddAccountRoute() {
  const intl = useIntl();
  const router = useRouter();
  const { portfolioId } = useLocalSearchParams<{ portfolioId: string }>();
  const portfolio = usePortfolio(portfolioId ?? '');
  const gone = portfolio.isError && isNotFound(portfolio.error);

  // The portfolio was removed meanwhile: back to S1 with a toast (add-account.md).
  useEffect(() => {
    if (gone) leaveRemovedPortfolio(router);
  }, [gone, router]);

  return (
    <ShellScreen title={intl.formatMessage({ id: 'addAccount.title' })}>
      <View style={{ width: '100%', maxWidth: 720, alignSelf: 'center', padding: Spacing.three }}>
        {portfolio.isPending ? <LoadingSkeleton count={4} /> : null}
        {portfolio.isError && !gone ? (
          <ErrorBanner error={portfolio.error} onRetry={() => void portfolio.refetch()} />
        ) : null}
        {portfolio.data ? (
          <AccountForm
            portfolio={portfolio.data}
            onCancel={() => router.replace('/overview')}
            onFinished={() => router.replace('/overview')}
            onPortfolioGone={() => leaveRemovedPortfolio(router)}
            onImport={(accountId) =>
              router.replace({
                pathname: '/accounts/[accountId]/imports/new',
                params: { accountId },
              } as Href)
            }
          />
        ) : null}
      </View>
    </ShellScreen>
  );
}

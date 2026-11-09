import { useRouter, type Href } from 'expo-router';
import { useIntl } from 'react-intl';
import { View } from 'react-native';

import { useWorkspace } from '@/api/queries/resources';
import { ErrorBanner, LoadingSkeleton } from '@/components/shared';
import { Spacing } from '@/constants/theme';
import { PortfolioForm } from '@/features/add-flow/portfolio-form';
import { ShellScreen } from '@/features/shell/screen';

export default function AddPortfolioRoute() {
  const router = useRouter();
  const intl = useIntl();
  const workspace = useWorkspace();
  return (
    <ShellScreen title={intl.formatMessage({ id: 'addPortfolio.title' })}>
      <View style={{ width: '100%', maxWidth: 720, alignSelf: 'center', padding: Spacing.three }}>
        {workspace.isPending ? <LoadingSkeleton count={4} /> : null}
        {workspace.isError ? (
          <ErrorBanner error={workspace.error} onRetry={() => void workspace.refetch()} />
        ) : null}
        {workspace.data ? (
          <PortfolioForm
            workspaceCurrency={workspace.data.currency}
            onCancel={() => router.replace('/overview')}
            onCreated={(portfolio) =>
              router.replace({
                pathname: '/portfolios/[portfolioId]/accounts/new',
                params: { portfolioId: portfolio.id },
              } as Href)
            }
          />
        ) : null}
      </View>
    </ShellScreen>
  );
}

import { Redirect, useLocalSearchParams, useRouter, type Href } from 'expo-router';
import { useIntl } from 'react-intl';

import { useWorkspace } from '@/api/queries/resources';
import { isAdministrator } from '@/features/auth/session';
import { useIdentity } from '@/features/auth/use-identity';
import { PortfolioScreen } from '@/features/portfolio-view/portfolio-screen';
import { homeFor } from '@/features/shell/navigation';
import { ShellScreen } from '@/features/shell/screen';

/** S2 remains inside Overview; the shell owns financial-data screen protection. */
export default function PortfolioRoute() {
  const intl = useIntl();
  const router = useRouter();
  const identity = useIdentity();
  const workspace = useWorkspace();
  const { portfolioId } = useLocalSearchParams<{ portfolioId: string }>();
  if (isAdministrator(identity)) return <Redirect href={homeFor(true)} />;
  return (
    <ShellScreen title={intl.formatMessage({ id: 'nav.overview' })}>
      <PortfolioScreen
        portfolioId={portfolioId ?? ''}
        overviewName={workspace.data?.name ?? null}
        onOverview={() => router.replace('/overview')}
        onMissing={() => router.replace('/overview')}
        onPortfolio={(id) =>
          router.replace({
            pathname: '/overview/[portfolioId]',
            params: { portfolioId: id },
          })
        }
        onAddPortfolio={() => router.push('/portfolios/new' as Href)}
        onAddAccount={(id) =>
          router.push({
            pathname: '/portfolios/[portfolioId]/accounts/new',
            params: { portfolioId: id },
          } as Href)
        }
        onImport={() => router.push('/imports/new' as Href)}
        onAccount={(id) =>
          router.push({
            pathname: '/accounts/[accountId]',
            params: { accountId: id },
          } as Href)
        }
      />
    </ShellScreen>
  );
}

import { Redirect, useLocalSearchParams, useRouter } from 'expo-router';

import { isAdministrator } from '@/features/auth/session';
import { useIdentity } from '@/features/auth/use-identity';
import { InvestmentsScreen } from '@/features/investments/investments-screen';
import { homeFor } from '@/features/shell/navigation';

export default function InvestmentsRoute() {
  const identity = useIdentity();
  const router = useRouter();
  const { account } = useLocalSearchParams<{ account?: string | string[] }>();
  if (isAdministrator(identity)) return <Redirect href={homeFor(true)} />;

  return (
    <InvestmentsScreen
      accountId={typeof account === 'string' ? account : undefined}
      onScope={(id) =>
        router.replace({ pathname: '/investments', params: id ? { account: id } : {} })
      }
      onAddDepot={(portfolioId) =>
        router.push(
          portfolioId
            ? { pathname: '/portfolios/[portfolioId]/accounts/new', params: { portfolioId } }
            : '/portfolios/new',
        )
      }
      onAccount={(accountId, tab) =>
        router.push({ pathname: '/accounts/[accountId]', params: { accountId, tab } })
      }
    />
  );
}

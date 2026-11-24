import { Redirect, useLocalSearchParams, useRouter } from 'expo-router';

import { isAdministrator } from '@/features/auth/session';
import { useIdentity } from '@/features/auth/use-identity';
import { InvestmentsScreen } from '@/features/investments/investments-screen';
import { homeFor } from '@/features/shell/navigation';

export default function InvestmentsRoute() {
  const identity = useIdentity();
  const router = useRouter();
  const { account, currency } = useLocalSearchParams<{
    account?: string | string[];
    currency?: string | string[];
  }>();
  if (isAdministrator(identity)) return <Redirect href={homeFor(true)} />;
  // The Overview scope currency, so the subtotal matches the Depot tile that led here.
  const scopeCurrency =
    typeof currency === 'string' && /^[A-Z]{3}$/.test(currency) ? currency : undefined;

  return (
    <InvestmentsScreen
      accountId={typeof account === 'string' ? account : undefined}
      currency={scopeCurrency}
      onScope={(id) =>
        router.replace({
          pathname: '/investments',
          params: {
            ...(id ? { account: id } : {}),
            ...(scopeCurrency ? { currency: scopeCurrency } : {}),
          },
        })
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

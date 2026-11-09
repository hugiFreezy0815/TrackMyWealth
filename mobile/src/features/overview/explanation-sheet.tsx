import type { IntlShape } from 'react-intl';
import { Text } from 'react-native';

import { Amount, Dialog } from '@/components/shared';
import { TypeScale } from '@/constants/theme';
import { formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';
import { dataWarningMessageIds, unknownValuations, type NetWorth } from './overview-model';
import { InfoText, msg } from './overview-text';
import type { FinancialPart } from './totals-cards';

const PARTS: Record<
  FinancialPart,
  { title: string; value: 'netWorth' | 'totalAssets' | 'totalLiabilities' }
> = {
  netWorth: { title: 'overview.netWorth', value: 'netWorth' },
  assets: { title: 'overview.assets', value: 'totalAssets' },
  liabilities: { title: 'overview.liabilities', value: 'totalLiabilities' },
};

/**
 * NFR-UX-003: what the figure includes, its date, currency, estimate flag, the accounts with an
 * unknown value, and the data-quality warnings in plain words (never the server's codes).
 */
export function ExplanationSheet({
  part,
  data,
  hidden,
  close,
  intl,
}: {
  part: FinancialPart | null;
  data?: NetWorth;
  hidden: boolean;
  close: () => void;
  intl: IntlShape;
}) {
  const theme = useTheme();
  if (!data || !part) return null;
  const unknown = unknownValuations(data);
  return (
    <Dialog title={msg(intl, PARTS[part].title)} visible onClose={close}>
      <Amount
        value={data[PARTS[part].value]}
        currency={data.currency}
        masked={hidden}
        style={TypeScale.headingMedium}
      />
      <InfoText>
        {msg(intl, 'overview.explain.asOf', { date: formatDate(data.asOf, intl.locale) })}
      </InfoText>
      <InfoText>{msg(intl, 'overview.explain.currency', { currency: data.currency })}</InfoText>
      {data.approximate ? <InfoText>{msg(intl, 'overview.explain.approximate')}</InfoText> : null}
      {!data.complete ? (
        <>
          <InfoText warning>{msg(intl, 'total.incomplete')}</InfoText>
          <Text
            accessibilityRole="header"
            style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}
          >
            {msg(intl, 'overview.explain.unknown')}
          </Text>
          {unknown.length ? (
            unknown.map((account) => <InfoText key={account.accountId}>{account.name}</InfoText>)
          ) : (
            <InfoText>{msg(intl, 'overview.explain.none')}</InfoText>
          )}
        </>
      ) : (
        <InfoText>{msg(intl, 'overview.explain.complete')}</InfoText>
      )}
      {dataWarningMessageIds(data.warnings).map((id) => (
        <InfoText key={id} warning>
          {msg(intl, id)}
        </InfoText>
      ))}
    </Dialog>
  );
}

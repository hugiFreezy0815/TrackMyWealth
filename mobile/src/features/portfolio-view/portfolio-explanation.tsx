import { useIntl } from 'react-intl';
import { Text } from 'react-native';

import { Amount, Dialog, ValueStatusBadge } from '@/components/shared';
import { TypeScale } from '@/constants/theme';
import { dataWarningMessageIds } from '@/features/overview/overview-model';
import { InfoText, msg } from '@/features/overview/overview-text';
import { formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';

import type { HeaderData } from './portfolio-header';
import {
  sectionMessageKey,
  unknownAccounts,
  type PortfolioSection,
  type PortfolioView,
} from './model';

export type Explained = 'total' | PortfolioSection;

/**
 * Spec S2, "Tap the total or a subtotal": currency, `asOf`, `complete`, the warnings in plain
 * words (never the server's codes), the accounts with an unknown value and the "≈" conversion
 * basis. Everything is loaded data; nothing is recalculated.
 */
export function PortfolioExplanation({
  explained,
  data,
  view,
  onClose,
}: {
  explained: Explained | null;
  data: HeaderData;
  view: PortfolioView | undefined;
  onClose: () => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  if (!explained) return null;
  const isTotal = explained === 'total';
  const section = isTotal ? null : explained;
  const title = section
    ? msg(intl, sectionMessageKey(section.section))
    : msg(intl, 'portfolio.total');
  const amount = section ? section.subtotal : data.total;
  const converted = section ? section.subtotalInWorkspaceCurrency : data.totalInWorkspaceCurrency;
  const status = section ? section.valueStatus : data.valueStatus;
  const complete = section ? section.complete : data.complete;
  // The server sends warnings for the whole portfolio, not per section.
  const warnings = isTotal ? dataWarningMessageIds(view?.warnings ?? []) : [];
  const unknown = unknownAccounts(section ? [section] : (view?.sections ?? []));
  const known = status !== 'UNKNOWN';
  return (
    <Dialog title={title} visible onClose={onClose}>
      <Amount
        value={amount}
        currency={data.currency}
        valueKnown={known}
        style={TypeScale.headingMedium}
      />
      {data.currency !== data.workspaceCurrency && converted != null ? (
        <Amount
          approx
          testID="portfolio-explanation-converted"
          value={converted}
          currency={data.workspaceCurrency}
          valueKnown={known}
          style={TypeScale.bodyMedium}
        />
      ) : null}
      <ValueStatusBadge status={status} />
      {data.asOf ? (
        <InfoText>
          {msg(intl, 'portfolio.asOf', { date: formatDate(data.asOf, intl.locale) })}
        </InfoText>
      ) : null}
      <InfoText>{msg(intl, 'overview.explain.currency', { currency: data.currency })}</InfoText>
      {data.currency !== data.workspaceCurrency ? (
        <InfoText>
          {msg(intl, 'portfolio.explain.converted', { currency: data.workspaceCurrency })}
        </InfoText>
      ) : null}
      {complete ? (
        <InfoText>{msg(intl, 'overview.explain.complete')}</InfoText>
      ) : (
        <InfoText warning>{msg(intl, 'total.incomplete')}</InfoText>
      )}
      {unknown.length ? (
        <>
          <Text
            accessibilityRole="header"
            style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}
          >
            {msg(intl, 'overview.explain.unknown')}
          </Text>
          {unknown.map((account) => (
            <InfoText key={account.accountId}>{account.name}</InfoText>
          ))}
        </>
      ) : null}
      {warnings.map((id) => (
        <InfoText key={id} warning>
          {msg(intl, id)}
        </InfoText>
      ))}
    </Dialog>
  );
}

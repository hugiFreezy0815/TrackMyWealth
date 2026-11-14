import { useState, type ReactNode } from 'react';
import { useIntl } from 'react-intl';
import { Pressable, Text, View } from 'react-native';

import {
  Amount,
  Figure,
  InstitutionLogo,
  SectionCard,
  ValueStatusBadge,
  amountTexts,
} from '@/components/shared';
import type { ValueStatus } from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { isNegativeAmount } from '@/features/overview/overview-model';
import { msg } from '@/features/overview/overview-text';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';

import { typeMessageKey, type PortfolioListItem, type PortfolioView } from './model';

/**
 * The header fields, from the view or, until it arrives (or when it fails), from the S1 list item,
 * so name, logo and total never flash empty (spec S2, "Requests").
 */
export type HeaderData = {
  name: string;
  type: string;
  logoUrl: string | null;
  personalAssetsDefault: boolean;
  institutionName: string | null;
  country: string | null;
  currency: string;
  workspaceCurrency: string;
  total: string | null;
  totalInWorkspaceCurrency: string | null;
  valueStatus: ValueStatus;
  complete: boolean;
  asOf: string | null;
};

export function headerData(
  view: PortfolioView | undefined,
  item: PortfolioListItem | undefined,
  unknownName: string,
): HeaderData {
  return {
    name: view?.name ?? item?.name ?? unknownName,
    type: view?.portfolioType ?? item?.portfolioType ?? '',
    logoUrl: view?.logoUrl ?? item?.logoUrl ?? null,
    personalAssetsDefault: view?.personalAssetsDefault ?? item?.personalAssetsDefault ?? false,
    institutionName: view?.institutionName ?? item?.institutionName ?? null,
    country: item?.country ?? null,
    currency: view?.portfolioCurrency ?? item?.portfolioCurrency ?? '',
    workspaceCurrency: view?.workspaceCurrency ?? item?.workspaceCurrency ?? '',
    total: view?.total ?? item?.total ?? null,
    totalInWorkspaceCurrency:
      view?.totalInWorkspaceCurrency ?? item?.totalInWorkspaceCurrency ?? null,
    valueStatus: view?.valueStatus ?? item?.valueStatus ?? 'UNKNOWN',
    complete: view?.complete ?? item?.complete ?? false,
    asOf: view?.asOf ?? item?.asOf ?? null,
  };
}

/** Header #8 `[advanced]`: the server's rate and valuation date, marked when carried forward. */
function FxHint({ view, data }: { view: PortfolioView; data: HeaderData }) {
  const intl = useIntl();
  const theme = useTheme();
  const [open, setOpen] = useState(false);
  if (!view.conversionRate || !view.conversionRateDate) return null;
  const marker = view.conversionRateStale
    ? 'portfolio.fx.stale'
    : view.conversionRateCarriedForward
      ? 'portfolio.fx.carried'
      : null;
  return (
    <View style={{ gap: Spacing.one }}>
      <Pressable
        accessibilityRole="button"
        aria-expanded={open}
        onPress={() => setOpen(!open)}
        style={{ minHeight: 44, justifyContent: 'center', alignSelf: 'flex-start' }}
      >
        <Text style={{ color: theme.brandLink }}>{msg(intl, 'portfolio.fx.details')}</Text>
      </Pressable>
      {open ? (
        <View
          testID="portfolio-fx-rate"
          style={{
            flexDirection: 'row',
            flexWrap: 'wrap',
            alignItems: 'baseline',
            gap: Spacing.one,
          }}
        >
          <Text style={{ color: theme.textSecondary }}>
            {msg(intl, 'portfolio.fx.rateFrom', { from: data.currency })}
          </Text>
          <Figure value={view.conversionRate} kind="rate" />
          <Text style={{ color: theme.textSecondary }}>
            {msg(intl, 'portfolio.fx.rateTo', {
              to: data.workspaceCurrency,
              date: formatDate(view.conversionRateDate, intl.locale),
            })}
          </Text>
          {marker ? (
            <Text
              style={{
                color: view.conversionRateStale ? theme.statusWarning : theme.textSecondary,
              }}
            >
              {msg(intl, marker)}
            </Text>
          ) : null}
        </View>
      ) : null}
    </View>
  );
}

export function PortfolioHeader({
  data,
  view,
  onExplain,
  children,
}: {
  data: HeaderData;
  view: PortfolioView | undefined;
  onExplain: () => void;
  children?: ReactNode;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const hidden = useAmountsHidden();
  const known = data.valueStatus !== 'UNKNOWN';
  const typeKey = typeMessageKey(data.type);
  return (
    <SectionCard title={data.name}>
      <View
        style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: Spacing.two }}
      >
        <InstitutionLogo
          name={data.name}
          logoUrl={data.logoUrl}
          personalAssetsDefault={data.personalAssetsDefault}
        />
        {typeKey ? (
          <Text style={{ ...TypeScale.labelMedium, color: theme.textSecondary }}>
            {msg(intl, typeKey)}
          </Text>
        ) : null}
      </View>
      <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
        {data.institutionName
          ? msg(intl, data.country ? 'portfolio.subtitle' : 'portfolio.subtitleNoCountry', {
              institution: data.institutionName,
              country: data.country ?? '',
              currency: data.currency,
            })
          : msg(intl, 'portfolio.currencyOnly', { currency: data.currency })}
      </Text>
      <Pressable
        accessibilityRole="button"
        testID="portfolio-total-action"
        accessibilityLabel={msg(intl, 'portfolio.totalExplain', {
          name: data.name,
          amount: amountTexts(intl, {
            value: data.total,
            currency: data.currency,
            masked: hidden,
            valueKnown: known,
          }).label,
        })}
        onPress={onExplain}
        style={{ minHeight: 48, justifyContent: 'center' }}
      >
        <Amount
          value={data.total}
          currency={data.currency}
          testID="portfolio-total"
          valueKnown={known}
          style={{
            ...TypeScale.headingLarge,
            // FR-NAV-15: never clamped; loss colour only while the sign is visible anyway.
            color: !hidden && isNegativeAmount(data.total) ? theme.statusLoss : theme.textPrimary,
          }}
        />
      </Pressable>
      <ValueStatusBadge status={data.valueStatus} />
      {!data.complete ? (
        <Text accessibilityRole="alert" style={{ color: theme.statusWarning }}>
          {msg(intl, 'total.incomplete')}
        </Text>
      ) : null}
      {data.currency !== data.workspaceCurrency && data.totalInWorkspaceCurrency != null ? (
        <Amount
          approx
          value={data.totalInWorkspaceCurrency}
          currency={data.workspaceCurrency}
          valueKnown={known}
          style={TypeScale.bodyMedium}
        />
      ) : null}
      {view ? <FxHint view={view} data={data} /> : null}
      {children}
    </SectionCard>
  );
}

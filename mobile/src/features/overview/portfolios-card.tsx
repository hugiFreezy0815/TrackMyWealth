import type { IntlShape } from 'react-intl';
import { Pressable, Text, View } from 'react-native';

import {
  Amount,
  Figure,
  InstitutionLogo,
  SectionCard,
  ValueStatusBadge,
} from '@/components/shared';
import { figureTexts } from '@/components/shared/figure';
import { TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import type { Portfolio } from './overview-model';
import { InfoText, msg } from './overview-text';

/**
 * S1 #17–#22. Show a portfolio's change only when the list provides it for the active period.
 * The "≈" line shows only when the portfolio and overview currencies differ.
 */
export function PortfoliosCard({
  items,
  hidden,
  intl,
  onPortfolio,
  period,
}: {
  items: readonly Portfolio[];
  hidden: boolean;
  intl: IntlShape;
  period: string;
  onPortfolio?: (id: string) => void;
}) {
  const theme = useTheme();
  return (
    <SectionCard title={msg(intl, 'overview.portfolios')}>
      {items.length === 0 ? <InfoText>{msg(intl, 'overview.portfolio.empty')}</InfoText> : null}
      {items.map((item) => {
        const percent =
          item.valueStatus === 'CURRENT' && item.change?.period === period
            ? item.change.percent
            : null;
        const hint =
          onPortfolio && !hidden && percent != null
            ? msg(intl, 'history.rowChange', {
                change: figureTexts(intl, { value: percent, kind: 'percent', signRole: 'change' })
                  .label,
              })
            : undefined;
        return (
          <Pressable
            key={item.id}
            accessibilityRole={onPortfolio ? 'button' : undefined}
            accessibilityLabel={onPortfolio ? item.name : undefined}
            accessibilityHint={hint}
            onPress={onPortfolio ? () => onPortfolio(item.id) : undefined}
            style={{
              flexDirection: 'row',
              alignItems: 'flex-start',
              gap: 8,
              paddingVertical: 10,
              borderBottomWidth: 1,
              borderBottomColor: theme.borderDefault,
            }}
          >
            <InstitutionLogo
              name={item.name}
              logoUrl={item.logoUrl}
              personalAssetsDefault={item.personalAssetsDefault}
            />
            <View style={{ flex: 1, minWidth: 0, gap: 4 }}>
              <Text
                style={{ ...TypeScale.labelMedium, fontWeight: '600', color: theme.textPrimary }}
              >
                {item.name}
              </Text>
              <InfoText>
                {item.personalAssetsDefault
                  ? msg(intl, 'overview.portfolio.personalAssets')
                  : msg(intl, 'overview.portfolio.subtitle', {
                      count: item.activeAccountCount,
                      currency: item.portfolioCurrency,
                    })}
              </InfoText>
              <ValueStatusBadge status={item.valueStatus} interactive={!onPortfolio} />
              {!item.complete ? <InfoText warning>{msg(intl, 'total.incomplete')}</InfoText> : null}
            </View>
            {/* M03: the amounts sit right of the name; they never shrink, the name wraps. */}
            <View style={{ alignItems: 'flex-end', flexShrink: 0, maxWidth: '50%', gap: 6 }}>
              <Amount
                value={item.total}
                currency={item.portfolioCurrency}
                masked={hidden}
                valueKnown={item.valueStatus !== 'UNKNOWN'}
                style={TypeScale.numberLarge}
              />
              {percent != null ? (
                <Figure
                  value={percent}
                  kind="percent"
                  signRole="change"
                  tone="gainLoss"
                  masked={hidden}
                />
              ) : null}
              {item.portfolioCurrency !== item.workspaceCurrency ? (
                <Amount
                  value={item.totalInWorkspaceCurrency}
                  currency={item.workspaceCurrency}
                  masked={hidden}
                  approx
                  valueKnown={item.valueStatus !== 'UNKNOWN'}
                  style={{ ...TypeScale.caption, color: theme.textSecondary }}
                />
              ) : null}
            </View>
          </Pressable>
        );
      })}
    </SectionCard>
  );
}

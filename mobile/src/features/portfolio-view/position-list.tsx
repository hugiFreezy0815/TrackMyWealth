import { useIntl } from 'react-intl';
import { Pressable, Text, View } from 'react-native';

import { Amount, Figure, ValueStatusBadge, amountTexts, figureTexts } from '@/components/shared';
import { isValueKnown } from '@/components/shared/value-status';
import { Spacing, TypeScale } from '@/constants/theme';
import { msg } from '@/features/overview/overview-text';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { useTheme } from '@/hooks/use-theme';

import type { Position } from './model';

export type PositionViewProps = {
  positions: readonly Position[];
  /** The depot's own currency: `valueInAccountCurrency` and `unrealisedGain` are in it. */
  currency: string;
  accountName: string;
  onOpen: () => void;
};

/**
 * Why a position shows "–" as its gain (spec S2, M24): another price currency than the depot
 * (FR-PERF-018), or no cost basis yet.
 */
export function noGainReason(position: Position, currency: string): string {
  return position.priceCurrency && position.priceCurrency !== currency
    ? 'portfolio.position.foreignGain'
    : 'portfolio.position.noBasis';
}

/** The row's spoken summary: name, value, units and gain, masked like the visible text. */
export function usePositionLabel(position: Position, currency: string): string {
  const intl = useIntl();
  const hidden = useAmountsHidden();
  const value = amountTexts(intl, {
    value: position.valueInAccountCurrency,
    currency,
    valueKnown: isValueKnown(position.valueStatus),
    masked: hidden,
  }).label;
  const units = figureTexts(intl, { value: position.quantity, kind: 'quantity', masked: hidden });
  const gain =
    position.unrealisedGainPercent != null
      ? msg(intl, 'portfolio.position.gainLabel', {
          percent: figureTexts(intl, {
            value: position.unrealisedGainPercent,
            kind: 'percent',
            signRole: 'change',
            masked: hidden,
          }).label,
        })
      : msg(intl, noGainReason(position, currency), {
          currency: position.priceCurrency ?? '',
        });
  return [
    position.displayName,
    value,
    msg(intl, 'portfolio.positions.units', { units: units.label }),
    gain,
  ].join(', ');
}

function PositionRow({
  position,
  currency,
  onOpen,
}: {
  position: Position;
  currency: string;
  onOpen: () => void;
}) {
  const theme = useTheme();
  const intl = useIntl();
  const hidden = useAmountsHidden();
  const units = figureTexts(intl, { value: position.quantity, kind: 'quantity', masked: hidden });
  const label = usePositionLabel(position, currency);
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={msg(intl, 'portfolio.position.openHint')}
      onPress={onOpen}
      style={{
        minHeight: 44,
        borderBottomWidth: 1,
        borderBottomColor: theme.borderDefault,
        paddingVertical: Spacing.two,
        gap: Spacing.one,
      }}
    >
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.two }}>
        <View style={{ flex: 1, minWidth: 120, gap: Spacing.one }}>
          <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>
            {position.displayName}
          </Text>
          <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
            {msg(
              intl,
              position.isin ? 'portfolio.positions.unitsIsin' : 'portfolio.positions.units',
              {
                units: units.display,
                isin: position.isin ?? '',
              },
            )}
          </Text>
        </View>
        <View style={{ alignItems: 'flex-end', gap: Spacing.one }}>
          <Amount
            value={position.valueInAccountCurrency}
            currency={currency}
            valueKnown={isValueKnown(position.valueStatus)}
          />
          {/* A null gain shows "–"; the reason follows in the status line below. */}
          <Figure
            value={position.unrealisedGainPercent}
            kind="percent"
            signRole="change"
            tone="gainLoss"
            style={TypeScale.caption}
          />
        </View>
      </View>
      <View
        style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: Spacing.two }}
      >
        <ValueStatusBadge status={position.valueStatus} interactive={false} />
        {position.unrealisedGainPercent == null ? (
          <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
            {msg(intl, noGainReason(position, currency), {
              currency: position.priceCurrency ?? '',
            })}
          </Text>
        ) : null}
        {position.costBasisEstimated ? (
          <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
            {msg(intl, 'portfolio.position.estimated')}
          </Text>
        ) : null}
      </View>
    </Pressable>
  );
}

/** Native (and narrow web) `PositionRow` list, M06; the web table is in position-view.web.tsx. */
export function PositionList({ positions, currency, onOpen }: PositionViewProps) {
  return (
    <View style={{ gap: Spacing.one }}>
      {positions.map((position) => (
        <PositionRow
          key={position.securityId}
          position={position}
          currency={currency}
          onOpen={onOpen}
        />
      ))}
    </View>
  );
}

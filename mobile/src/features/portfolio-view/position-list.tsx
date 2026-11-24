import { useIntl } from 'react-intl';
import { Pressable, Text, View } from 'react-native';

import {
  Amount,
  Figure,
  InstitutionLogo,
  ValueStatusBadge,
  amountTexts,
  figureTexts,
} from '@/components/shared';
import { isValueKnown, valueStatusTexts } from '@/components/shared/value-status';
import { Spacing, TypeScale } from '@/constants/theme';
import { msg } from '@/features/overview/overview-text';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { useTheme } from '@/hooks/use-theme';

import type { Position } from './model';
import { shownGain } from './position-gain';

export type PositionViewProps = {
  positions: readonly Position[];
  /** The depot's own currency: `valueInAccountCurrency` is in it. */
  currency: string;
  accountName: string;
  /** Optional: S3 displays positions read-only until a position-detail route exists. */
  onOpen?: () => void;
  investments?: { asOf: string | null; showDepotName: boolean };
};

/** A message id and its values. */
export type NoGainReason = { id: string; values?: Record<string, string> };

/**
 * Why a position shows "–" as its gain (spec S2, M24), or null when it has a gain or its value is
 * unknown (the value-status badge says so): no cost basis yet, a cost basis whose currency is
 * unknown, or one in a known currency that gives no gain - a third currency, or a foreign one
 * without its cost in the depot currency (FR-PERF-018, #527).
 */
export function noGainReason(
  position: Position,
  currency: string,
  investments = false,
): NoGainReason | null {
  if (shownGain(position, currency)) return null;
  if (position.costBasis == null) {
    return { id: investments ? 'investments.noBasis' : 'portfolio.position.noBasis' };
  }
  if (!isValueKnown(position.valueStatus)) return null;
  return position.costBasisCurrency
    ? {
        id: 'portfolio.position.noGainInBasisCurrency',
        values: { currency: position.costBasisCurrency },
      }
    : { id: 'portfolio.position.unknownBasisCurrency' };
}

/** The explanation line under a position without a gain; nothing when there is no reason. */
export function NoGainNote({
  position,
  currency,
  investments = false,
}: {
  position: Position;
  currency: string;
  investments?: boolean;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const reason = noGainReason(position, currency, investments);
  if (!reason) return null;
  return (
    <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
      {msg(intl, reason.id, reason.values)}
    </Text>
  );
}

/** The row's spoken summary: name, value, units and gain, masked like the visible text. */
export function usePositionLabel(
  position: Position,
  currency: string,
  asOf?: string | null,
  depotName?: string,
): string {
  const intl = useIntl();
  const hidden = useAmountsHidden();
  const value = amountTexts(intl, {
    value: position.valueInAccountCurrency,
    currency,
    valueKnown: isValueKnown(position.valueStatus),
    masked: hidden,
  }).label;
  const units = figureTexts(intl, { value: position.quantity, kind: 'quantity', masked: hidden });
  const reason = noGainReason(position, currency, asOf !== undefined);
  const shown = shownGain(position, currency);
  const gain = reason
    ? msg(intl, reason.id, reason.values)
    : msg(intl, 'portfolio.position.gainLabel', {
        percent: figureTexts(intl, {
          value: shown?.percent ?? null,
          kind: 'percent',
          signRole: 'change',
          masked: hidden,
        }).label,
      });
  const gainAmount =
    asOf !== undefined && shown && !hidden
      ? amountTexts(intl, { value: shown.amount, currency: shown.currency, signRole: 'change' })
          .label
      : null;
  const price =
    asOf === undefined && position.price != null && position.priceCurrency
      ? amountTexts(intl, {
          value: position.price,
          currency: position.priceCurrency,
          masked: hidden,
        }).label
      : null;
  return [
    position.displayName,
    depotName,
    value,
    asOf !== undefined ? valueStatusTexts(position.valueStatus, asOf, intl)?.label : undefined,
    msg(intl, 'portfolio.positions.units', { units: units.label }),
    ...(price ? [msg(intl, 'portfolio.positions.price') + ': ' + price] : []),
    gain,
    gainAmount,
  ]
    .filter(Boolean)
    .join(', ');
}

function PositionRow({
  position,
  currency,
  onOpen,
  investments,
  accountName,
}: {
  position: Position;
  currency: string;
  onOpen?: () => void;
  investments?: PositionViewProps['investments'];
  accountName: string;
}) {
  const theme = useTheme();
  const intl = useIntl();
  const hidden = useAmountsHidden();
  const units = figureTexts(intl, { value: position.quantity, kind: 'quantity', masked: hidden });
  const depotName = investments?.showDepotName ? accountName : undefined;
  const label = usePositionLabel(position, currency, investments?.asOf, depotName);
  const gain = shownGain(position, currency);
  const rowStyle = {
    minHeight: 44,
    borderBottomWidth: 1,
    borderBottomColor: theme.borderDefault,
    paddingVertical: Spacing.two,
    gap: Spacing.one,
  };
  const content = (
    <>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.two }}>
        {investments ? (
          <View aria-hidden importantForAccessibility="no-hide-descendants">
            <InstitutionLogo name={position.displayName} />
          </View>
        ) : null}
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
          {depotName ? (
            <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>{depotName}</Text>
          ) : null}
          {!investments && position.price != null && position.priceCurrency ? (
            <View
              style={{
                flexDirection: 'row',
                flexWrap: 'wrap',
                alignItems: 'center',
                gap: Spacing.one,
              }}
            >
              <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
                {msg(intl, 'portfolio.positions.price')}
              </Text>
              <Amount
                value={position.price}
                currency={position.priceCurrency}
                style={TypeScale.caption}
              />
            </View>
          ) : null}
        </View>
        <View style={{ alignItems: 'flex-end', gap: Spacing.one }}>
          <Amount
            value={position.valueInAccountCurrency}
            currency={currency}
            valueKnown={isValueKnown(position.valueStatus)}
          />
          {gain != null ? (
            <Amount
              value={gain.amount}
              currency={gain.currency}
              signRole="change"
              style={TypeScale.caption}
            />
          ) : null}
          {(!investments || gain?.percent != null) && (
            <Figure
              value={gain?.percent ?? null}
              kind="percent"
              signRole="change"
              tone="gainLoss"
              style={TypeScale.caption}
            />
          )}
        </View>
      </View>
      <View
        style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: Spacing.two }}
      >
        <ValueStatusBadge
          status={position.valueStatus}
          valueSourceDate={investments?.asOf}
          interactive={false}
        />
        <NoGainNote position={position} currency={currency} investments={Boolean(investments)} />
        {position.costBasisEstimated ? (
          <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
            {msg(intl, 'portfolio.position.estimated')}
          </Text>
        ) : null}
      </View>
    </>
  );
  return onOpen ? (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={msg(intl, 'portfolio.position.openHint')}
      onPress={onOpen}
      style={rowStyle}
    >
      {content}
    </Pressable>
  ) : (
    <View accessible accessibilityLabel={label} style={rowStyle}>
      {content}
    </View>
  );
}

/** Native (and narrow web) `PositionRow` list, M06; the web table is in position-view.web.tsx. */
export function PositionList({
  positions,
  currency,
  onOpen,
  investments,
  accountName,
}: PositionViewProps) {
  return (
    <View style={{ gap: Spacing.one }}>
      {positions.map((position) => (
        <PositionRow
          key={position.securityId}
          position={position}
          currency={currency}
          onOpen={onOpen}
          investments={investments}
          accountName={accountName}
        />
      ))}
    </View>
  );
}

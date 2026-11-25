import type { CSSProperties } from 'react';
import { useIntl } from 'react-intl';
import { Pressable, Text, View, useWindowDimensions } from 'react-native';

import { Amount, Figure, ValueStatusBadge } from '@/components/shared';
import { linkKeyProps } from '@/components/shared/link-key';
import { isValueKnown } from '@/components/shared/value-status';
import { Spacing, TypeScale } from '@/constants/theme';
import { msg } from '@/features/overview/overview-text';
import { useTheme } from '@/hooks/use-theme';

import {
  PositionList,
  CostBasisNote,
  NoGainNote,
  showsGainPercent,
  usePositionLabel,
  type PositionViewProps,
} from './position-list';
import { POSITION_TABLE_MIN_WIDTH, type Position, type PositionSort } from './model';
import { shownGain } from './position-gain';

const SR_ONLY: CSSProperties = {
  position: 'absolute',
  width: 1,
  height: 1,
  overflow: 'hidden',
  clipPath: 'inset(50%)',
  whiteSpace: 'nowrap',
};

function PositionCell({
  position,
  currency,
  onOpen,
  depotName,
  asOf,
}: {
  position: Position;
  currency: string;
  onOpen?: () => void;
  depotName?: string;
  asOf?: string | null;
}) {
  const theme = useTheme();
  const intl = useIntl();
  const label = usePositionLabel(position, currency, asOf, depotName);
  const content = (
    <>
      <Text
        style={{ ...TypeScale.labelMedium, color: onOpen ? theme.brandLink : theme.textPrimary }}
      >
        {position.displayName}
      </Text>
      {position.isin ? (
        <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>{position.isin}</Text>
      ) : null}
      {depotName ? (
        <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>{depotName}</Text>
      ) : null}
      {/* Shown on Investments too, unlike the phone row: W16 puts "price {price}" in this cell
          because the table has no Price column (PO 2026-11-22). */}
      {position.price != null && position.priceCurrency ? (
        <View
          style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: Spacing.one }}
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
    </>
  );
  const style = { minHeight: 44, justifyContent: 'center' as const, gap: Spacing.one };
  return onOpen ? (
    <Pressable
      accessibilityRole="link"
      accessibilityLabel={label}
      accessibilityHint={msg(intl, 'portfolio.position.openHint')}
      onPress={onOpen}
      {...linkKeyProps(onOpen)}
      style={style}
    >
      {content}
    </Pressable>
  ) : (
    <View accessible accessibilityLabel={label} style={style}>
      {content}
    </View>
  );
}

/**
 * W02/W03 and ADR 0007 (Accepted): on web the depot positions are a semantic `<table>` with a
 * caption and `<th scope="col">` headers: Position (name + ISIN) | Units | Value | Unrealised
 * gain (amount + %). Narrow viewports fall back to the native list.
 */
export function PositionView(props: PositionViewProps) {
  const { positions, currency, accountName, onOpen, investments } = props;
  const theme = useTheme();
  const intl = useIntl();
  const { width } = useWindowDimensions();
  if (width < POSITION_TABLE_MIN_WIDTH) return <PositionList {...props} />;
  const cell: CSSProperties = {
    padding: `${Spacing.two}px ${Spacing.compact}px`,
    borderBottom: `1px solid ${theme.borderDefault}`,
    verticalAlign: 'top',
  };
  // Long security names may break anywhere; amounts and units must never break mid-number.
  const nameCell: CSSProperties = { ...cell, overflowWrap: 'anywhere' };
  const headerText = {
    ...TypeScale.caption,
    fontWeight: '600',
    color: theme.textSecondary,
  } as const;
  const numeric: CSSProperties = { textAlign: 'end' };
  function column(key: PositionSort['key'], labelId: string) {
    const label = msg(intl, labelId);
    const onSort = investments?.onSort;
    const direction = investments?.sort?.key === key ? investments.sort.direction : undefined;
    return (
      <th
        key={key}
        scope="col"
        aria-sort={direction}
        style={{ ...cell, textAlign: key === 'name' ? 'start' : 'end' }}
      >
        {onSort ? (
          <button
            type="button"
            onClick={() => onSort(key)}
            style={{
              ...headerText,
              minHeight: 44,
              padding: 0,
              border: 0,
              background: 'transparent',
              color: theme.brandLink,
              cursor: 'pointer',
            }}
          >
            <Text style={{ color: theme.brandLink }}>{label}</Text>
            {direction ? (
              <Text aria-hidden style={{ color: theme.brandLink }}>
                {direction === 'ascending' ? ' ↑' : ' ↓'}
              </Text>
            ) : null}
          </button>
        ) : (
          <Text style={headerText}>{label}</Text>
        )}
      </th>
    );
  }
  return (
    <table
      data-testid="positions-table"
      style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'auto' }}
    >
      <caption style={SR_ONLY}>
        <Text>{msg(intl, 'portfolio.positions.caption', { account: accountName })}</Text>
      </caption>
      <thead>
        <tr>
          {column('name', 'portfolio.positions.column.position')}
          {column('units', 'portfolio.positions.column.units')}
          {investments ? column('costBasis', 'investments.costBasis') : null}
          {column('value', 'portfolio.positions.column.value')}
          {column('gain', 'portfolio.positions.column.gain')}
        </tr>
      </thead>
      <tbody>
        {positions.map((position) => (
          <tr key={position.securityId}>
            <td style={nameCell}>
              <PositionCell
                position={position}
                currency={currency}
                onOpen={onOpen}
                depotName={investments?.showDepotName ? accountName : undefined}
                asOf={investments?.asOf}
              />
            </td>
            <td style={{ ...cell, ...numeric }}>
              <Figure value={position.quantity} kind="quantity" style={TypeScale.bodyMedium} />
            </td>
            {investments ? (
              <td style={{ ...cell, ...numeric }}>
                <Amount
                  value={position.costBasis}
                  currency={position.costBasisCurrency ?? currency}
                  valueKnown={position.costBasisCurrency != null}
                />
              </td>
            ) : null}
            <td style={{ ...cell, ...numeric }}>
              <View style={{ alignItems: 'flex-end', gap: Spacing.one }}>
                <Amount
                  value={position.valueInAccountCurrency}
                  currency={currency}
                  valueKnown={isValueKnown(position.valueStatus)}
                />
                <ValueStatusBadge
                  status={position.valueStatus}
                  valueSourceDate={investments?.asOf}
                />
              </View>
            </td>
            <td style={{ ...cell, ...numeric }}>
              <View style={{ alignItems: 'flex-end', gap: Spacing.one }}>
                <GainFigures
                  position={position}
                  currency={currency}
                  omitMissing={Boolean(investments)}
                />
                <NoGainNote
                  position={position}
                  currency={currency}
                  investments={Boolean(investments)}
                />
                <CostBasisNote position={position} />
              </View>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * The gain amount in its own currency and its percentage (#527); "–" without a gain unless
 * Investments explains it in a note.
 */
function GainFigures({
  position,
  currency,
  omitMissing,
}: {
  position: Position;
  currency: string;
  omitMissing: boolean;
}) {
  const gain = shownGain(position, currency);
  return (
    <>
      {gain != null ? (
        <Amount
          value={gain.amount}
          currency={gain.currency}
          signRole="change"
          style={TypeScale.bodyMedium}
        />
      ) : null}
      {showsGainPercent(position, currency, omitMissing) && (
        <Figure
          value={gain?.percent ?? null}
          kind="percent"
          signRole="change"
          tone="gainLoss"
          style={TypeScale.caption}
        />
      )}
    </>
  );
}

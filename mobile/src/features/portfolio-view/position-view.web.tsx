import type { CSSProperties } from 'react';
import { useIntl } from 'react-intl';
import { Pressable, Text, View, useWindowDimensions } from 'react-native';

import { Amount, Figure, ValueStatusBadge } from '@/components/shared';
import { isValueKnown } from '@/components/shared/value-status';
import { Spacing, TypeScale } from '@/constants/theme';
import { msg } from '@/features/overview/overview-text';
import { useTheme } from '@/hooks/use-theme';

import {
  PositionList,
  NoGainNote,
  usePositionLabel,
  type PositionViewProps,
} from './position-list';
import type { Position } from './model';

/** Below the tablet breakpoint four columns do not fit 320 CSS px (200 % zoom): use the list. */
export const POSITION_TABLE_MIN_WIDTH = 600;

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
}: {
  position: Position;
  currency: string;
  onOpen: () => void;
}) {
  const theme = useTheme();
  const intl = useIntl();
  const label = usePositionLabel(position, currency);
  return (
    <Pressable
      accessibilityRole="link"
      accessibilityLabel={label}
      accessibilityHint={msg(intl, 'portfolio.position.openHint')}
      onPress={onOpen}
      style={{ minHeight: 44, justifyContent: 'center', gap: Spacing.one }}
    >
      <Text style={{ ...TypeScale.labelMedium, color: theme.brandLink }}>
        {position.displayName}
      </Text>
      {position.isin ? (
        <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>{position.isin}</Text>
      ) : null}
    </Pressable>
  );
}

/**
 * W02/W03 and ADR 0007 (Accepted): on web the depot positions are a semantic `<table>` with a
 * caption and `<th scope="col">` headers: Position (name + ISIN) | Units | Value | Unrealised
 * gain (amount + %). Narrow viewports fall back to the native list.
 */
export function PositionView(props: PositionViewProps) {
  const { positions, currency, accountName, onOpen } = props;
  const theme = useTheme();
  const intl = useIntl();
  const { width } = useWindowDimensions();
  if (width < POSITION_TABLE_MIN_WIDTH) return <PositionList {...props} />;
  const cell: CSSProperties = {
    padding: `${Spacing.two}px ${Spacing.compact}px`,
    borderBottom: `1px solid ${theme.borderDefault}`,
    verticalAlign: 'top',
  };
  const header: CSSProperties = cell;
  const headerText = {
    ...TypeScale.caption,
    fontWeight: '600',
    color: theme.textSecondary,
  } as const;
  const numeric: CSSProperties = { textAlign: 'end' };
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
          <th scope="col" style={{ ...header, textAlign: 'start' }}>
            <Text style={headerText}>{msg(intl, 'portfolio.positions.column.position')}</Text>
          </th>
          <th scope="col" style={{ ...header, ...numeric }}>
            <Text style={headerText}>{msg(intl, 'portfolio.positions.column.units')}</Text>
          </th>
          <th scope="col" style={{ ...header, ...numeric }}>
            <Text style={headerText}>{msg(intl, 'portfolio.positions.column.value')}</Text>
          </th>
          <th scope="col" style={{ ...header, ...numeric }}>
            <Text style={headerText}>{msg(intl, 'portfolio.positions.column.gain')}</Text>
          </th>
        </tr>
      </thead>
      <tbody>
        {positions.map((position) => (
          <tr key={position.securityId}>
            <td style={cell}>
              <PositionCell position={position} currency={currency} onOpen={onOpen} />
            </td>
            <td style={{ ...cell, ...numeric }}>
              <Figure value={position.quantity} kind="quantity" style={TypeScale.bodyMedium} />
            </td>
            <td style={{ ...cell, ...numeric }}>
              <View style={{ alignItems: 'flex-end', gap: Spacing.one }}>
                <Amount
                  value={position.valueInAccountCurrency}
                  currency={currency}
                  valueKnown={isValueKnown(position.valueStatus)}
                />
                <ValueStatusBadge status={position.valueStatus} />
              </View>
            </td>
            <td style={{ ...cell, ...numeric }}>
              <View style={{ alignItems: 'flex-end', gap: Spacing.one }}>
                {position.unrealisedGain != null ? (
                  <Amount
                    value={position.unrealisedGain}
                    currency={currency}
                    signRole="change"
                    style={TypeScale.bodyMedium}
                  />
                ) : null}
                <Figure
                  value={position.unrealisedGainPercent}
                  kind="percent"
                  signRole="change"
                  tone="gainLoss"
                  style={TypeScale.caption}
                />
                <NoGainNote position={position} currency={currency} />
                {position.costBasisEstimated ? (
                  <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
                    {msg(intl, 'portfolio.position.estimated')}
                  </Text>
                ) : null}
              </View>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

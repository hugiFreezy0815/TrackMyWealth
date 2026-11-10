import { useIntl } from 'react-intl';
import { Text, type TextProps } from 'react-native';

import type { DecimalString } from '@/api/decimal';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import type { AmountIntl } from '@/format/amount';
import {
  a11yFigure,
  figureDirection,
  formatFigure,
  type FigureKind,
  type FigureOptions,
} from '@/format/figure';
import { useTheme } from '@/hooks/use-theme';

const UNKNOWN = '–';

export type FigureValue = FigureOptions & { value: DecimalString | null; kind: FigureKind };

/**
 * Display and accessible text of a unit count, percentage or rate. A value this version cannot
 * format shows as unknown ("–") instead of failing the screen, as `amountTexts` does.
 */
export function figureTexts(
  intl: AmountIntl,
  { value, kind, ...options }: FigureValue,
): { display: string; label: string } {
  try {
    return {
      display: formatFigure(value, kind, intl.locale, options),
      label: a11yFigure(value, kind, intl, options),
    };
  } catch (error) {
    if (process.env.NODE_ENV !== 'production') {
      console.warn('Figure cannot be displayed:', (error as Error).message);
    }
    return {
      display: options.masked ? '••••' : UNKNOWN,
      label: intl.formatMessage({ id: options.masked ? 'value.hidden' : 'value.unknown' }),
    };
  }
}

export type FigureProps = FigureValue &
  Omit<TextProps, 'children'> & {
    /** Colour the text by the displayed sign (gain green, loss red); neutral while masked. */
    tone?: 'neutral' | 'gainLoss';
  };

/**
 * FR-CLI-005 / UX-02 §7: "Hide amounts" masks every amount, change and percentage. Units and FX
 * rates are masked too: units times a public price would reveal the hidden value.
 */
export function Figure({
  value,
  kind,
  signRole,
  masked,
  tone = 'neutral',
  style,
  accessibilityLabel,
  ...textProps
}: FigureProps) {
  const theme = useTheme();
  const intl = useIntl();
  const hidden = useAmountsHidden() || masked === true;
  const { display, label } = figureTexts(intl, { value, kind, signRole, masked: hidden });
  const direction = tone === 'gainLoss' && !hidden ? figureDirection(display) : 'flat';
  const color =
    direction === 'up'
      ? theme.statusGain
      : direction === 'down'
        ? theme.statusLoss
        : theme.textSecondary;
  return (
    <Text
      {...textProps}
      // Masking wins over any caller-provided label: private digits cannot leak.
      accessibilityLabel={hidden ? label : (accessibilityLabel ?? label)}
      style={[{ color, fontVariant: ['tabular-nums'], flexShrink: 1 }, style]}
    >
      {display}
    </Text>
  );
}

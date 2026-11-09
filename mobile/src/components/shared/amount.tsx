import { useIntl } from 'react-intl';
import { Text, type TextProps } from 'react-native';

import type { DecimalString } from '@/api/decimal';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { a11yAmount, formatAmount, type AmountIntl, type AmountOptions } from '@/format/amount';
import { useTheme } from '@/hooks/use-theme';

const UNKNOWN = '–';

export type AmountValue = AmountOptions & {
  value: DecimalString | null;
  currency: string;
};

/**
 * Display and accessible text for an amount from the API, formatted by `@/format/amount` (#486).
 * Response data is an open set: a value or currency this version cannot format shows as unknown
 * ("–") instead of failing the screen.
 */
export function amountTexts(
  intl: AmountIntl,
  { value, currency, ...options }: AmountValue,
): { display: string; label: string } {
  try {
    return {
      display: formatAmount(value, currency, intl.locale, options),
      label: a11yAmount(value, currency, intl, options),
    };
  } catch (error) {
    if (process.env.NODE_ENV !== 'production') {
      console.warn('Amount cannot be displayed:', (error as Error).message);
    }
    return {
      display: options.masked ? '••••' : UNKNOWN,
      label: intl.formatMessage({ id: options.masked ? 'value.hidden' : 'value.unknown' }),
    };
  }
}

export type AmountProps = AmountValue & Omit<TextProps, 'children'>;

/**
 * The one component that renders money (#488, FR-CLI-005): "Hide amounts" (#487) masks it on
 * every screen, as does `masked`. PrivacyAmount renders through it.
 */
export function Amount({
  value,
  currency,
  signRole,
  approx,
  valueKnown,
  masked,
  style,
  accessibilityLabel,
  ...textProps
}: AmountProps) {
  const theme = useTheme();
  const intl = useIntl();
  const hidden = useAmountsHidden() || masked === true;
  const { display, label } = amountTexts(intl, {
    value,
    currency,
    signRole,
    approx,
    valueKnown,
    masked: hidden,
  });
  return (
    <Text
      {...textProps}
      // Masking wins over any caller-provided label: private digits cannot leak.
      accessibilityLabel={hidden ? label : (accessibilityLabel ?? label)}
      style={[{ color: theme.textPrimary, fontVariant: ['tabular-nums'], flexShrink: 1 }, style]}
    >
      {display}
    </Text>
  );
}

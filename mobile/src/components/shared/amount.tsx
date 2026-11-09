import { useIntl } from 'react-intl';
import { Text, type TextStyle } from 'react-native';

import type { DecimalString } from '@/api/decimal';
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

export type AmountProps = AmountValue & {
  style?: TextStyle;
  accessibilityLabel?: string;
  testID?: string;
};
export function Amount({ style, accessibilityLabel, testID, ...amount }: AmountProps) {
  const theme = useTheme();
  const intl = useIntl();
  const { display, label } = amountTexts(intl, amount);
  return (
    <Text
      testID={testID}
      // Masking wins over any caller-provided label: private digits cannot leak.
      accessibilityLabel={amount.masked ? label : (accessibilityLabel ?? label)}
      style={[{ color: theme.textPrimary, fontVariant: ['tabular-nums'], flexShrink: 1 }, style]}
    >
      {display}
    </Text>
  );
}

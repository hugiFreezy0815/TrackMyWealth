import { Text, type TextProps } from 'react-native';
import { useIntl } from 'react-intl';
import { a11yAmount, formatAmount, type AmountOptions } from '@/format/amount';
import type { DecimalString } from '@/api/decimal';
import { useAmountsHidden } from './amount-visibility';

type Props = Omit<TextProps, 'children'> & {
  value: DecimalString | null;
  currency: string;
  options?: AmountOptions;
};
export function PrivacyAmount({ value, currency, options, ...props }: Props) {
  const { locale } = useIntl();
  const hidden = useAmountsHidden();
  const settings = { ...options, masked: hidden || options?.masked };
  return (
    <Text {...props} accessibilityLabel={a11yAmount(value, currency, locale, settings)}>
      {formatAmount(value, currency, locale, settings)}
    </Text>
  );
}

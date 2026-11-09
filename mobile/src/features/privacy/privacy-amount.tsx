import type { TextProps } from 'react-native';

import type { DecimalString } from '@/api/decimal';
import { Amount } from '@/components/shared/amount';
import type { AmountOptions } from '@/format/amount';

type Props = Omit<TextProps, 'children'> & {
  value: DecimalString | null;
  currency: string;
  options?: AmountOptions;
};

/** FR-CLI-005 (#487): money that honours "Hide amounts"; rendered by the shared Amount (#488). */
export function PrivacyAmount({ value, currency, options, ...props }: Props) {
  return <Amount {...props} {...options} value={value} currency={currency} />;
}

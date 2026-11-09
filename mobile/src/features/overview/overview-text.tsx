import type { ReactNode } from 'react';
import type { IntlShape } from 'react-intl';
import { Text } from 'react-native';

import { TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

export type MessageValues = Record<string, string | number>;

export function msg(intl: IntlShape, id: string, values?: MessageValues): string {
  return intl.formatMessage({ id }, values);
}

/** Secondary copy on the overview cards; `warning` uses the warning ink. */
export function InfoText({
  children,
  warning = false,
}: {
  children: ReactNode;
  warning?: boolean;
}) {
  const theme = useTheme();
  return (
    <Text
      style={{
        ...TypeScale.bodyMedium,
        color: warning ? theme.statusWarning : theme.textSecondary,
        flexShrink: 1,
      }}
    >
      {children}
    </Text>
  );
}

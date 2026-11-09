import { useIntl } from 'react-intl';
import { Text, View } from 'react-native';

import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

import type { DateFieldProps } from './date-field.shared';
import { FieldMessages, isValidIsoDate } from './fields';

export { isoToLocalDate, localDateToIso, type DateFieldProps } from './date-field.shared';

/**
 * Web build of DateField: @react-native-community/datetimepicker has no web implementation, so
 * the browser's own date picker (`<input type="date">`) chooses the date. Its value is already
 * `YYYY-MM-DD`; the browser shows it in the user's locale.
 */
export function DateField({
  label,
  value,
  onChange,
  minimumDate,
  maximumDate,
  error,
  hint,
  disabled = false,
  testID,
}: DateFieldProps) {
  const theme = useTheme();
  const intl = useIntl();
  return (
    <View style={{ gap: Spacing.one }}>
      <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>{label}</Text>
      <input
        type="date"
        data-testid={testID}
        aria-label={label}
        aria-invalid={error ? true : undefined}
        lang={intl.locale}
        value={value ?? ''}
        min={minimumDate}
        max={maximumDate}
        disabled={disabled}
        onChange={(event) => {
          if (isValidIsoDate(event.target.value)) onChange(event.target.value);
        }}
        style={{
          ...TypeScale.bodyLarge,
          // CSS reads a unitless line-height as a multiple of the font size.
          lineHeight: `${TypeScale.bodyLarge.lineHeight}px`,
          fontFamily: 'inherit',
          minHeight: 44,
          boxSizing: 'border-box',
          borderStyle: 'solid',
          borderWidth: 1,
          borderColor: error ? theme.statusLoss : theme.borderInteractive,
          borderRadius: Radii.control,
          paddingLeft: Spacing.compact,
          paddingRight: Spacing.compact,
          color: theme.textPrimary,
          backgroundColor: theme.bgSurface,
          colorScheme: 'light dark',
        }}
      />
      <FieldMessages hint={hint} error={error} />
    </View>
  );
}

import DateTimePicker, {
  DateTimePickerAndroid,
  type DateTimePickerEvent,
} from '@react-native-community/datetimepicker';
import { useState } from 'react';
import { useIntl } from 'react-intl';
import { Platform, Pressable, Text, View } from 'react-native';

import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';

import { ActionButton, BottomSheet } from './feedback';
import {
  isoToLocalDate,
  localDateToIso,
  optionalLocalDate,
  type DateFieldProps,
} from './date-field.shared';
import { FieldMessages, isValidIsoDate } from './fields';

export { isoToLocalDate, localDateToIso, type DateFieldProps } from './date-field.shared';

/**
 * PO decision 2026-11-09 (#488): dates are chosen with the system date picker (Android dialog,
 * iOS inline calendar in a bottom sheet), never typed. The web build uses date-field.web.tsx.
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
  const [pending, setPending] = useState<Date | null>(null);
  const current = value && isValidIsoDate(value) ? isoToLocalDate(value) : null;
  const shown = value
    ? formatDate(value, intl.locale)
    : intl.formatMessage({ id: 'ui.chooseDate' });
  const limits = {
    minimumDate: optionalLocalDate(minimumDate),
    maximumDate: optionalLocalDate(maximumDate),
  };

  const open = () => {
    const initial = current ?? new Date();
    if (Platform.OS === 'android') {
      DateTimePickerAndroid.open({
        value: initial,
        mode: 'date',
        ...limits,
        onChange: (event: DateTimePickerEvent, date?: Date) => {
          if (event.type === 'set' && date) onChange(localDateToIso(date));
        },
      });
    } else {
      setPending(initial);
    }
  };

  return (
    <View style={{ gap: Spacing.one }}>
      <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>{label}</Text>
      <Pressable
        testID={testID}
        accessibilityRole="button"
        accessibilityLabel={label + ', ' + shown}
        accessibilityHint={error ?? hint}
        accessibilityState={{ disabled }}
        disabled={disabled}
        onPress={open}
        style={{
          minHeight: 44,
          justifyContent: 'center',
          borderColor: error ? theme.statusLoss : theme.borderInteractive,
          borderWidth: 1,
          borderRadius: Radii.control,
          paddingHorizontal: Spacing.compact,
          backgroundColor: theme.bgSurface,
          opacity: disabled ? 0.5 : 1,
        }}
      >
        <Text
          style={{ ...TypeScale.bodyLarge, color: value ? theme.textPrimary : theme.textTertiary }}
        >
          {shown}
        </Text>
      </Pressable>
      <FieldMessages hint={hint} error={error} />
      <BottomSheet title={label} visible={pending !== null} onClose={() => setPending(null)}>
        {pending ? (
          <DateTimePicker
            testID={testID ? testID + '-picker' : undefined}
            value={pending}
            mode="date"
            display="inline"
            locale={intl.locale}
            {...limits}
            onChange={(_event: DateTimePickerEvent, date?: Date) => {
              if (date) setPending(date);
            }}
          />
        ) : null}
        <ActionButton
          label={intl.formatMessage({ id: 'ui.done' })}
          onPress={() => {
            if (pending) onChange(localDateToIso(pending));
            setPending(null);
          }}
        />
      </BottomSheet>
    </View>
  );
}

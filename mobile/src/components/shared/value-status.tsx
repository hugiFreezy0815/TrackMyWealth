import { useState } from 'react';
import { useIntl, type IntlShape } from 'react-intl';
import { Pressable, Text, View } from 'react-native';

import { labelFor, type DisplayEnum } from '@/api/enum-label';
import type { components } from '@/api/schema';
import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';

import { BottomSheet } from './feedback';

/** The generated contract enum (#375); every response with a `valueStatus` shares it. */
export type KnownValueStatus = components['schemas']['AccountListItemResponse']['valueStatus'];
/** Open at runtime (#452): a newer server may send a status this app does not know. */
export type ValueStatus = DisplayEnum<KnownValueStatus>;

const KNOWN_STATUSES = {
  CURRENT: 'CURRENT',
  ESTIMATED: 'ESTIMATED',
  STALE: 'STALE',
  UNRECONCILED: 'UNRECONCILED',
  UNKNOWN: 'UNKNOWN',
} as const satisfies Record<KnownValueStatus, KnownValueStatus>;

/** Spec README §3: an unknown enum value is treated as `UNKNOWN`, never as a known status. */
export function recognizedValueStatus(status: ValueStatus | null | undefined): KnownValueStatus {
  if (status == null) return 'CURRENT';
  return labelFor<KnownValueStatus>(KNOWN_STATUSES, status, 'UNKNOWN') as KnownValueStatus;
}

/** Whether an amount with this status can be shown; an `UNKNOWN` value is shown as "–", never 0. */
export function isValueKnown(status: ValueStatus | null | undefined): boolean {
  return recognizedValueStatus(status) !== 'UNKNOWN';
}

const MESSAGES: Record<
  Exclude<KnownValueStatus, 'CURRENT'>,
  { label: string; explanation: string }
> = {
  ESTIMATED: {
    label: 'valueStatus.estimated',
    explanation: 'valueStatus.estimated.explanation',
  },
  STALE: { label: 'valueStatus.stale', explanation: 'valueStatus.stale.explanation' },
  UNRECONCILED: {
    label: 'valueStatus.unreconciled',
    explanation: 'valueStatus.unreconciled.explanation',
  },
  UNKNOWN: { label: 'valueStatus.unknown', explanation: 'valueStatus.unknown.explanation' },
};

/** The badge label and explanation for a status; null for `CURRENT` (no badge). */
export function valueStatusTexts(
  status: ValueStatus | null | undefined,
  valueSourceDate: string | null | undefined,
  intl: Pick<IntlShape, 'locale' | 'formatMessage'>,
): { label: string; detail: string } | null {
  const recognized = recognizedValueStatus(status);
  if (recognized === 'CURRENT') return null;
  const messages = MESSAGES[recognized];
  const label =
    recognized === 'STALE' && !valueSourceDate
      ? intl.formatMessage({ id: 'valueStatus.staleWithoutDate' })
      : intl.formatMessage(
          { id: messages.label },
          { date: valueSourceDate ? formatDate(valueSourceDate, intl.locale) : '' },
        );
  return { label, detail: intl.formatMessage({ id: messages.explanation }) };
}

type Props = {
  status: ValueStatus | null | undefined;
  valueSourceDate?: string | null;
  /**
   * False inside a pressable row: a button nested in a button is unreachable for screen readers
   * and competes for taps, so the row offers the explanation as an accessibility action instead.
   */
  interactive?: boolean;
  testID?: string;
};

export function ValueStatusBadge({ status, valueSourceDate, interactive = true, testID }: Props) {
  const theme = useTheme();
  const intl = useIntl();
  const [expanded, setExpanded] = useState(false);
  const recognized = recognizedValueStatus(status);
  const texts = valueStatusTexts(status, valueSourceDate, intl);
  if (recognized === 'CURRENT' || !texts) return null;
  const { label, detail } = texts;
  const ink =
    recognized === 'UNRECONCILED'
      ? theme.statusWarning
      : recognized === 'ESTIMATED'
        ? theme.statusInfo
        : theme.textPrimary;
  const background =
    recognized === 'UNRECONCILED'
      ? theme.statusWarningSubtle
      : recognized === 'ESTIMATED'
        ? theme.statusInfoSubtle
        : theme.bgSubtle;
  const style = {
    paddingHorizontal: Spacing.compact,
    minHeight: interactive ? 44 : undefined,
    paddingVertical: interactive ? undefined : Spacing.one,
    alignSelf: 'flex-start',
    justifyContent: 'center',
    borderRadius: Radii.pill,
    backgroundColor: background,
  } as const;
  const text = (
    <Text style={{ ...TypeScale.labelSmall, fontWeight: '600', color: ink }}>{label}</Text>
  );
  if (!interactive) {
    return (
      <View testID={testID} style={style}>
        {text}
      </View>
    );
  }
  return (
    <>
      <Pressable
        testID={testID}
        accessibilityRole="button"
        accessibilityLabel={label + '. ' + detail}
        accessibilityHint={intl.formatMessage({ id: 'valueStatus.explain' })}
        onPress={() => setExpanded(true)}
        style={style}
      >
        {text}
      </Pressable>
      <ValueStatusExplanation
        status={status}
        valueSourceDate={valueSourceDate}
        visible={expanded}
        onClose={() => setExpanded(false)}
      />
    </>
  );
}

/** The explanation sheet of a value status (spec README §3, "tap for more"). */
export function ValueStatusExplanation({
  status,
  valueSourceDate,
  visible,
  onClose,
}: {
  status: ValueStatus | null | undefined;
  valueSourceDate?: string | null;
  visible: boolean;
  onClose: () => void;
}) {
  const theme = useTheme();
  const intl = useIntl();
  const texts = valueStatusTexts(status, valueSourceDate, intl);
  if (!texts) return null;
  return (
    <BottomSheet
      title={texts.label}
      visible={visible}
      onClose={onClose}
      closeLabel={intl.formatMessage({ id: 'valueStatus.dismiss' })}
    >
      <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>{texts.detail}</Text>
    </BottomSheet>
  );
}

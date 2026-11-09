import { useState } from 'react';
import { Modal, Pressable, Text, View } from 'react-native';

import { labelFor, type DisplayEnum } from '@/api/enum-label';
import type { components } from '@/api/schema';
import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

import { currentUiLocale, displayDate, uiMessage, type MessageKey, type UiLocale } from './locale';

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
  { label: MessageKey; explanation: MessageKey }
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
  locale: UiLocale,
): { label: string; detail: string } | null {
  const recognized = recognizedValueStatus(status);
  if (recognized === 'CURRENT') return null;
  const messages = MESSAGES[recognized];
  const label =
    recognized === 'STALE' && !valueSourceDate
      ? uiMessage('valueStatus.staleWithoutDate', locale)
      : uiMessage(messages.label, locale, {
          date: valueSourceDate ? displayDate(valueSourceDate, locale) : '',
        });
  return { label, detail: uiMessage(messages.explanation, locale) };
}

type Props = {
  status: ValueStatus | null | undefined;
  valueSourceDate?: string | null;
  locale?: UiLocale;
  testID?: string;
};

export function ValueStatusBadge({
  status,
  valueSourceDate,
  locale = currentUiLocale(),
  testID,
}: Props) {
  const theme = useTheme();
  const [expanded, setExpanded] = useState(false);
  const recognized = recognizedValueStatus(status);
  const texts = valueStatusTexts(status, valueSourceDate, locale);
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
  return (
    <>
      <Pressable
        testID={testID}
        accessibilityRole="button"
        accessibilityLabel={label + '. ' + detail}
        accessibilityHint={uiMessage('valueStatus.explain', locale)}
        onPress={() => setExpanded(true)}
        style={{
          paddingHorizontal: Spacing.compact,
          minHeight: 44,
          alignSelf: 'flex-start',
          justifyContent: 'center',
          borderRadius: Radii.pill,
          backgroundColor: background,
        }}
      >
        <Text style={{ ...TypeScale.labelSmall, fontWeight: '600', color: ink }}>{label}</Text>
      </Pressable>
      <Modal
        transparent
        visible={expanded}
        animationType="fade"
        onRequestClose={() => setExpanded(false)}
      >
        <View
          style={{
            flex: 1,
            justifyContent: 'center',
            padding: Spacing.four,
            backgroundColor: theme.bgOverlay,
          }}
        >
          <View
            accessibilityViewIsModal
            style={{
              borderRadius: Radii.card,
              padding: Spacing.four,
              backgroundColor: theme.bgSurface,
            }}
          >
            <Text
              accessibilityRole="header"
              style={{ ...TypeScale.headingMedium, color: theme.textPrimary }}
            >
              {label}
            </Text>
            <Text
              style={{
                ...TypeScale.bodyMedium,
                color: theme.textSecondary,
                marginTop: Spacing.compact,
              }}
            >
              {detail}
            </Text>
            <Pressable
              accessibilityRole="button"
              onPress={() => setExpanded(false)}
              style={{ minHeight: 44, justifyContent: 'center', marginTop: Spacing.three }}
            >
              <Text style={{ ...TypeScale.labelMedium, color: theme.brandLink }}>
                {uiMessage('valueStatus.dismiss', locale)}
              </Text>
            </Pressable>
          </View>
        </View>
      </Modal>
    </>
  );
}

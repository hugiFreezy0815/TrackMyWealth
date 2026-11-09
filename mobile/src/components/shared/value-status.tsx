import { useState } from 'react';
import { Modal, Pressable, Text, View } from 'react-native';

import { Radii, Spacing } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

import { currentUiLocale, uiMessage, type UiLocale } from './locale';

export type ValueStatus = 'CURRENT' | 'ESTIMATED' | 'STALE' | 'UNRECONCILED' | 'UNKNOWN' | (string & {});

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
  if (!status || status === 'CURRENT') return null;
  const recognized: Exclude<ValueStatus, string & {}> | string =
    status === 'ESTIMATED' || status === 'STALE' || status === 'UNRECONCILED'
      ? status
      : 'UNKNOWN';
  const label =
    recognized === 'ESTIMATED'
      ? uiMessage('value.estimated', locale)
      : recognized === 'STALE'
        ? valueSourceDate
          ? uiMessage('value.stale', locale, { date: valueSourceDate })
          : uiMessage('value.stale', locale, { date: '–' })
        : recognized === 'UNRECONCILED'
          ? uiMessage('value.unreconciled', locale)
          : uiMessage('value.unknown', locale);
  const detail =
    recognized === 'ESTIMATED'
      ? uiMessage('value.explanation.estimated', locale)
      : recognized === 'STALE'
        ? uiMessage('value.explanation.stale', locale)
        : recognized === 'UNRECONCILED'
          ? uiMessage('value.explanation.unreconciled', locale)
          : uiMessage('value.explanation.unknown', locale);
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
        accessibilityHint={uiMessage('value.explain', locale)}
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
        <Text style={{ color: ink, fontSize: 12, lineHeight: 16, fontWeight: '600' }}>
          {label}
        </Text>
      </Pressable>
      <Modal transparent visible={expanded} animationType="fade" onRequestClose={() => setExpanded(false)}>
        <View style={{ flex: 1, justifyContent: 'center', padding: 24, backgroundColor: '#00000099' }}>
          <View style={{ borderRadius: Radii.card, padding: 24, backgroundColor: theme.bgSurface }}>
            <Text accessibilityRole="header" style={{ fontSize: 20, fontWeight: '600', color: theme.textPrimary }}>
              {label}
            </Text>
            <Text style={{ color: theme.textSecondary, marginTop: 12 }}>{detail}</Text>
            <Pressable
              accessibilityRole="button"
              onPress={() => setExpanded(false)}
              style={{ minHeight: 44, justifyContent: 'center', marginTop: 16 }}
            >
              <Text style={{ color: theme.brandLink }}>{uiMessage('value.dismiss', locale)}</Text>
            </Pressable>
          </View>
        </View>
      </Modal>
    </>
  );
}

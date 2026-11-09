import { useState } from 'react';
import { useIntl } from 'react-intl';
import { Pressable, Text, View } from 'react-native';

import { useWorkspace } from '@/api/queries/resources';
import { BottomSheet, ErrorBanner, LoadingSkeleton } from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

import { OverviewNameEditor } from './overview-name-editor';

/** Settings / Overview group: changing and clearing the first tab's name. */
export function OverviewNameSettings() {
  const intl = useIntl();
  const theme = useTheme();
  const workspace = useWorkspace();
  const [open, setOpen] = useState(false);
  const title = intl.formatMessage({ id: 'settings.overviewName' });
  const fallback = intl.formatMessage({ id: 'overview.allPortfolios' });

  return (
    <View style={{ gap: Spacing.two }}>
      <Text accessibilityRole="header" style={{ ...TypeScale.headingSmall, color: theme.textPrimary }}>
        {intl.formatMessage({ id: 'nav.overview' })}
      </Text>
      {workspace.isPending ? <LoadingSkeleton count={1} /> : null}
      {workspace.isError ? <ErrorBanner error={workspace.error} onRetry={() => void workspace.refetch()} /> : null}
      {workspace.data ? (
        <>
          <Pressable
            accessible
            accessibilityRole="button"
            accessibilityLabel={title}
            accessibilityHint={workspace.data.name ?? fallback}
            onPress={() => setOpen(true)}
            style={{
              paddingVertical: Spacing.compact,
              minHeight: 56,
              borderBottomWidth: 1,
              borderBottomColor: theme.borderDefault,
              gap: Spacing.one,
            }}
          >
            <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>{title}</Text>
            <Text style={{ ...TypeScale.bodyMedium,
              color: workspace.data.name ? theme.textPrimary : theme.textSecondary }}>
              {workspace.data.name ?? fallback}
            </Text>
          </Pressable>
          <BottomSheet title={title} visible={open} onClose={() => setOpen(false)}>
            {open ? (
              <OverviewNameEditor
                key={workspace.data.id}
                mode="settings"
                workspace={workspace.data}
                onFinished={() => setOpen(false)}
              />
            ) : null}
          </BottomSheet>
        </>
      ) : null}
    </View>
  );
}

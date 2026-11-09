import { useEffect, useState } from 'react';
import { onlineManager } from '@tanstack/react-query';
import { StyleSheet, View } from 'react-native';
import { useIntl } from 'react-intl';

import { ThemedText } from '@/components/themed-text';
import { useTheme } from '@/hooks/use-theme';

/** M24: shown above every signed-in screen while the device is offline. */
export function OfflineBanner() {
  const [online, setOnline] = useState(() => onlineManager.isOnline());
  const intl = useIntl();
  const theme = useTheme();
  useEffect(() => onlineManager.subscribe(setOnline), []);
  if (online) return null;
  return (
    <View
      accessible
      accessibilityRole="alert"
      style={[styles.banner, { backgroundColor: theme.warningBackground }]}
    >
      <ThemedText themeColor="onWarning" style={styles.label}>
        {intl.formatMessage({ id: 'error.offline' })}
      </ThemedText>
    </View>
  );
}

const styles = StyleSheet.create({
  banner: { paddingVertical: 12, paddingHorizontal: 16 },
  label: { fontSize: 14, lineHeight: 20 },
});

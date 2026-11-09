import { StyleSheet, View } from 'react-native';
import { useIntl } from 'react-intl';

import { ThemedText } from '@/components/themed-text';
import { useOnline } from '@/hooks/use-online';
import { useTheme } from '@/hooks/use-theme';

/** M24: shown above every signed-in screen while the device is offline. */
export function OfflineBanner() {
  const online = useOnline();
  const intl = useIntl();
  const theme = useTheme();
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

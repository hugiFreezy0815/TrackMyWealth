import { useEffect, useState } from 'react';
import { onlineManager } from '@tanstack/react-query';
import { View, StyleSheet } from 'react-native';
import { useIntl } from 'react-intl';
import { ThemedText } from '@/components/themed-text';

export function OfflineBanner() {
  const [online, setOnline] = useState(() => onlineManager.isOnline());
  const intl = useIntl();
  useEffect(() => onlineManager.subscribe(setOnline), []);
  if (online !== false) return null;
  return (
    <View accessible accessibilityRole="alert" style={styles.banner}>
      <ThemedText style={styles.label}>{intl.formatMessage({ id: 'error.offline' })}</ThemedText>
    </View>
  );
}
const styles = StyleSheet.create({
  banner: { paddingVertical: 12, paddingHorizontal: 16, backgroundColor: '#FFF2CF' },
  label: { color: '#4D3600', fontSize: 14, lineHeight: 20 },
});

import { useRouter } from 'expo-router';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { useIntl } from 'react-intl';
import { ThemedText } from '@/components/themed-text';
import { useTheme } from '@/hooks/use-theme';

export function ShellScreen({ title, children }: { title: string; children: React.ReactNode }) {
  const theme = useTheme();
  return (
    <ScrollView style={{ backgroundColor: theme.background }} contentContainerStyle={styles.scroll}>
      <View style={styles.content}>
        <ThemedText type="subtitle" style={styles.heading}>{title}</ThemedText>
        {children}
      </View>
    </ScrollView>
  );
}
/** Until its screen story ships, deep links fail safely without exposing financial data. */
export function UnavailableScreen() {
  const router = useRouter();
  const intl = useIntl();
  return (
    <ShellScreen title={intl.formatMessage({ id: 'route.unavailable.title' })}>
      <ThemedText>{intl.formatMessage({ id: 'route.unavailable.description' })}</ThemedText>
      <Pressable accessibilityRole="button" style={styles.button} onPress={() => router.replace('/overview')}>
        <ThemedText style={styles.buttonLabel}>{intl.formatMessage({ id: 'nav.overview' })}</ThemedText>
      </Pressable>
    </ShellScreen>
  );
}
const styles = StyleSheet.create({
  scroll: { flexGrow: 1, alignItems: 'center', padding: 24 },
  content: { width: '100%', maxWidth: 960, gap: 20 },
  heading: { fontSize: 28, lineHeight: 36 },
  button: { backgroundColor: '#075CB2', minHeight: 48, padding: 12, borderRadius: 10,
    alignItems: 'center', justifyContent: 'center', alignSelf: 'flex-start' },
  buttonLabel: { color: '#FFFFFF', fontWeight: '700' },
});

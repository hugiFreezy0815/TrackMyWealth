import { Pressable, StyleSheet } from 'react-native';
import { useRouter } from 'expo-router';
import { useIntl } from 'react-intl';
import { ThemedText } from '@/components/themed-text';
import { ShellScreen } from '@/features/shell/screen';

export default function MoreRoute() {
  const intl = useIntl();
  const router = useRouter();
  return (
    <ShellScreen title={intl.formatMessage({ id: 'nav.more' })}>
      <Pressable accessibilityRole="button" style={styles.item} onPress={() => router.push('/settings')}>
        <ThemedText>{intl.formatMessage({ id: 'nav.settings' })}</ThemedText>
      </Pressable>
    </ShellScreen>
  );
}
const styles = StyleSheet.create({ item: { minHeight: 48, paddingVertical: 12 } });

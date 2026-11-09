import { Pressable, StyleSheet, Switch, View } from 'react-native';
import { useIntl } from 'react-intl';

import { ThemedText } from '@/components/themed-text';
import { signOut } from '@/features/auth/session';
import { setAmountsHidden, useAmountsHidden } from '@/features/privacy/amount-visibility';
import { ShellScreen } from '@/features/shell/screen';

export default function SettingsRoute() {
  const intl = useIntl();
  const hidden = useAmountsHidden();
  return (
    <ShellScreen title={intl.formatMessage({ id: 'nav.settings' })}>
      <View style={styles.preference}>
        <View style={styles.label}>
          <ThemedText type="smallBold">
            {intl.formatMessage({ id: 'privacy.hideAmounts' })}
          </ThemedText>
          <ThemedText type="small" themeColor="textSecondary">
            {intl.formatMessage({ id: 'privacy.hideAmounts.detail' })}
          </ThemedText>
        </View>
        <Switch
          accessibilityLabel={intl.formatMessage({ id: 'privacy.hideAmounts' })}
          accessibilityRole="switch"
          value={hidden}
          onValueChange={(value) => void setAmountsHidden(value)}
        />
      </View>
      <Pressable accessibilityRole="button" style={styles.signOut} onPress={() => void signOut()}>
        <ThemedText style={styles.signOutLabel}>
          {intl.formatMessage({ id: 'auth.signOut' })}
        </ThemedText>
      </Pressable>
    </ShellScreen>
  );
}
const styles = StyleSheet.create({
  preference: { flexDirection: 'row', alignItems: 'center', gap: 16, minHeight: 56 },
  label: { flex: 1, gap: 4 },
  signOut: {
    minHeight: 48,
    padding: 12,
    backgroundColor: '#075CB2',
    borderRadius: 10,
    alignSelf: 'flex-start',
    justifyContent: 'center',
  },
  signOutLabel: { color: '#FFFFFF', fontWeight: '700' },
});

import { Pressable, StyleSheet, Switch, View } from 'react-native';
import { useIntl } from 'react-intl';

import { ThemedText } from '@/components/themed-text';
import { useTheme } from '@/hooks/use-theme';
import { setAmountsHidden, useAmountsHidden } from '@/features/privacy/amount-visibility';
import { ShellScreen } from '@/features/shell/screen';
import { useSignOut } from '@/features/shell/use-sign-out';

export default function SettingsRoute() {
  const intl = useIntl();
  const theme = useTheme();
  const hidden = useAmountsHidden();
  const signOut = useSignOut();
  return (
    <ShellScreen title={intl.formatMessage({ id: 'nav.settings' })} privacyToggle={false}>
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
      <Pressable
        accessibilityRole="button"
        style={[styles.signOut, { backgroundColor: theme.primary }]}
        onPress={() => void signOut()}
      >
        <ThemedText themeColor="onPrimary" style={styles.signOutLabel}>
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
    borderRadius: 10,
    alignSelf: 'flex-start',
    justifyContent: 'center',
  },
  signOutLabel: { fontWeight: '700' },
});

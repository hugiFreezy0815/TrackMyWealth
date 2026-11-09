import { Pressable, StyleSheet } from 'react-native';
import { useIntl } from 'react-intl';

import { ThemedText } from '@/components/themed-text';
import { useTheme } from '@/hooks/use-theme';

import { setAmountsHidden, useAmountsHidden } from './amount-visibility';

const EYE_OPEN = '◉';
const EYE_CLOSED = '◎';

/** The eye control (M03): one tap hides or shows every amount on every screen. */
export function AmountVisibilityToggle() {
  const intl = useIntl();
  const theme = useTheme();
  const hidden = useAmountsHidden();
  return (
    <Pressable
      accessibilityRole="switch"
      // The name states the effect; the switch state says whether it is on.
      accessibilityLabel={intl.formatMessage({ id: 'privacy.hideAmounts' })}
      accessibilityState={{ checked: hidden }}
      hitSlop={8}
      onPress={() => void setAmountsHidden(!hidden)}
      style={({ pressed }) => [
        styles.toggle,
        { borderColor: theme.border },
        pressed && styles.pressed,
      ]}
    >
      <ThemedText
        style={styles.icon}
        accessibilityElementsHidden
        importantForAccessibility="no-hide-descendants"
      >
        {hidden ? EYE_CLOSED : EYE_OPEN}
      </ThemedText>
      <ThemedText type="small">
        {intl.formatMessage({ id: hidden ? 'privacy.showAmounts' : 'privacy.hideAmounts' })}
      </ThemedText>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  toggle: {
    minHeight: 44,
    minWidth: 44,
    paddingHorizontal: 12,
    borderRadius: 22,
    borderWidth: StyleSheet.hairlineWidth,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  icon: { fontSize: 18 },
  pressed: { opacity: 0.7 },
});

import { NativeTabs } from 'expo-router/unstable-native-tabs';
import { useColorScheme } from 'react-native';

import { Colors } from '@/constants/theme';
import { isAdministrator, sessionSnapshot } from '@/features/auth/session';

export default function AppTabs() {
  const scheme = useColorScheme();
  const colors = Colors[scheme ?? 'light'];
  const admin = isAdministrator(sessionSnapshot().identity);

  return (
    <NativeTabs
      backgroundColor={colors.background}
      indicatorColor={colors.backgroundElement}
      labelStyle={{ selected: { color: colors.text } }}
    >
      <NativeTabs.Trigger name="index">
        <NativeTabs.Trigger.Label>Home</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon
          src={require('@/assets/images/tabIcons/home.png')}
          renderingMode="template"
        />
      </NativeTabs.Trigger>

      {admin && (
        <NativeTabs.Trigger name="reference-data">
          <NativeTabs.Trigger.Label>Reference data</NativeTabs.Trigger.Label>
          <NativeTabs.Trigger.Icon src={require('@/assets/images/tabIcons/explore.png')} renderingMode="template" />
        </NativeTabs.Trigger>
      )}

      <NativeTabs.Trigger name="explore">
        <NativeTabs.Trigger.Label>Explore</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon
          src={require('@/assets/images/tabIcons/explore.png')}
          renderingMode="template"
        />
      </NativeTabs.Trigger>
    </NativeTabs>
  );
}

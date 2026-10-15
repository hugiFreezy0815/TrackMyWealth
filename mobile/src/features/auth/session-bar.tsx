import { Button, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { ThemedText } from '@/components/themed-text';

import { Identity, isAdministrator, signOut } from './session';

export function SessionBar({ identity }: { identity: Identity | null }) {
  return (
    <SafeAreaView edges={['top']}>
      <View style={styles.bar}>
        <ThemedText type="small">
          {isAdministrator(identity) ? 'Signed in as administrator' : 'Signed in'}
        </ThemedText>
        <Button title="Sign out" onPress={() => void signOut()} />
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
  },
});

import { useState } from 'react';
import { Button, StyleSheet, TextInput, View } from 'react-native';

import { getApiBaseUrl } from '@/api/client';
import { InvalidServerUrlError, saveServerUrl } from '@/api/server-url';
import { ThemedText } from '@/components/themed-text';

import { cancelMfa, signIn, verifyMfa } from './session';

export function AuthScreen({ mfaRequired }: { mfaRequired: boolean }) {
  const [server, setServer] = useState(getApiBaseUrl);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      if (mfaRequired) {
        await verifyMfa(code);
      } else {
        // FR-CLI-007: the server is chosen before any credential is sent to it.
        setServer(await saveServerUrl(server));
        await signIn(email.trim(), password);
      }
    } catch (failure) {
      setError(
        failure instanceof InvalidServerUrlError
          ? failure.message
          : 'Could not sign in. Check your credentials or verification code.',
      );
    } finally {
      setCode('');
      setBusy(false);
    }
  }

  function startOver() {
    setError(null);
    setCode('');
    cancelMfa();
  }

  return (
    <View style={styles.container}>
      <ThemedText type="subtitle">{mfaRequired ? 'Verify sign-in' : 'Sign in'}</ThemedText>
      {!mfaRequired ? (
        <>
          <TextInput
            accessibilityLabel="Server"
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
            value={server}
            onChangeText={setServer}
            style={styles.field}
          />
          <TextInput
            accessibilityLabel="Email"
            autoCapitalize="none"
            autoComplete="email"
            keyboardType="email-address"
            value={email}
            onChangeText={setEmail}
            style={styles.field}
          />
          <TextInput
            accessibilityLabel="Password"
            secureTextEntry
            autoComplete="password"
            value={password}
            onChangeText={setPassword}
            style={styles.field}
          />
        </>
      ) : (
        <TextInput
          accessibilityLabel="Authenticator code"
          keyboardType="number-pad"
          autoComplete="one-time-code"
          maxLength={6}
          value={code}
          onChangeText={setCode}
          style={styles.field}
        />
      )}
      {error && <ThemedText accessibilityRole="alert">{error}</ThemedText>}
      <Button title={mfaRequired ? 'Verify code' : 'Sign in'} onPress={submit} disabled={busy} />
      {mfaRequired && <Button title="Start over" onPress={startOver} disabled={busy} />}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', padding: 24, gap: 14 },
  field: { borderWidth: 1, padding: 12, borderRadius: 8 },
});

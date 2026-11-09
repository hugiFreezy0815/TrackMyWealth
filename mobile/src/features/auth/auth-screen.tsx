import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, TextInput, type TextInputProps, View } from 'react-native';

import { getApiBaseUrl, NetworkError } from '@/api/client';
import { InvalidServerUrlError, saveServerUrl } from '@/api/server-url';
import { ThemedText } from '@/components/themed-text';
import { useTheme } from '@/hooks/use-theme';

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
        // Sent and accepted: not kept in memory through the MFA step.
        setPassword('');
      }
    } catch (failure) {
      setError(errorMessage(failure));
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

  const theme = useTheme();
  return (
    <View style={[styles.container, { backgroundColor: theme.background }]}>
      <View style={[styles.card, { backgroundColor: theme.backgroundElement }]}>
        <ThemedText type="smallBold" style={styles.brand}>TrackMyWealth</ThemedText>
      <ThemedText type="subtitle">{mfaRequired ? 'Verify sign-in' : 'Sign in'}</ThemedText>
      {!mfaRequired ? (
        <>
          <LabeledField
            label="Server"
            placeholder="https://wealth.example.com"
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
            value={server}
            onChangeText={setServer}
          />
          <LabeledField
            label="Email"
            autoCapitalize="none"
            autoComplete="email"
            keyboardType="email-address"
            value={email}
            onChangeText={setEmail}
          />
          <LabeledField
            label="Password"
            secureTextEntry
            autoComplete="password"
            value={password}
            onChangeText={setPassword}
          />
        </>
      ) : (
        <LabeledField
          label="Authenticator code"
          keyboardType="number-pad"
          autoComplete="one-time-code"
          maxLength={6}
          value={code}
          onChangeText={setCode}
        />
      )}
      {error && <ThemedText accessibilityRole="alert">{error}</ThemedText>}
      <Pressable accessibilityRole="button" accessibilityState={{ disabled: busy }}
        style={[styles.submit, busy && styles.disabled]} onPress={() => void submit()} disabled={busy}>
        {busy ? <ActivityIndicator color="#FFFFFF" /> :
          <ThemedText style={styles.submitText}>{mfaRequired ? 'Verify code' : 'Sign in'}</ThemedText>}
      </Pressable>
      {mfaRequired && (
        <Pressable accessibilityRole="button" accessibilityState={{ disabled: busy }}
          style={styles.back} onPress={startOver} disabled={busy}>
          <ThemedText type="smallBold">Start over</ThemedText>
        </Pressable>
      )}
      </View>
    </View>
  );
}

function errorMessage(failure: unknown): string {
  if (failure instanceof InvalidServerUrlError) return failure.message;
  // Not the credentials' fault: saying so would send the user hunting for a typo.
  if (failure instanceof NetworkError) {
    return 'Could not reach the server. Check the server address and your connection.';
  }
  return 'Could not sign in. Check your credentials or verification code.';
}

/** A visible label above the input; the same text is its accessible name. */
function LabeledField({ label, ...input }: TextInputProps & { label: string }) {
  const theme = useTheme();
  return (
    <View style={styles.labeled}>
      <ThemedText type="small" themeColor="textSecondary">
        {label}
      </ThemedText>
      <TextInput
        accessibilityLabel={label}
        placeholderTextColor={theme.textSecondary}
        style={[styles.field, { color: theme.text, borderColor: theme.textSecondary }]}
        {...input}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  card: { width: '100%', maxWidth: 440, borderRadius: 20, padding: 28, gap: 18 },
  brand: { color: '#075CB2', letterSpacing: 0.3 },
  labeled: { gap: 8 },
  field: { borderWidth: 1, minHeight: 48, padding: 12, borderRadius: 10 },
  submit: { minHeight: 48, backgroundColor: '#075CB2', borderRadius: 10,
    justifyContent: 'center', alignItems: 'center', marginTop: 8 },
  submitText: { color: '#FFFFFF', fontSize: 16, fontWeight: '700' },
  disabled: { opacity: 0.5 },
  back: { minHeight: 44, justifyContent: 'center', alignItems: 'center' },
});

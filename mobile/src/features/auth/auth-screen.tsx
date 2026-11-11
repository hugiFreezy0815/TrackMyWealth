import { type ComponentRef, type Ref, useRef, useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  TextInput,
  type TextInputProps,
  View,
} from 'react-native';
import { useIntl, type IntlShape } from 'react-intl';
import { SafeAreaView } from 'react-native-safe-area-context';

import { getApiBaseUrl, NetworkError } from '@/api/client';
import { InvalidServerUrlError, saveServerUrl } from '@/api/server-url';
import { ThemedText } from '@/components/themed-text';
import { useTheme } from '@/hooks/use-theme';

import { cancelMfa, signIn, verifyMfa } from './session';

const SERVER_PLACEHOLDER = 'https://wealth.example.com';

export function AuthScreen({ mfaRequired }: { mfaRequired: boolean }) {
  const intl = useIntl();
  const theme = useTheme();
  const passwordInput = useRef<ComponentRef<typeof TextInput>>(null);
  const submitting = useRef(false);
  const [server, setServer] = useState(getApiBaseUrl);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (submitting.current) return;
    submitting.current = true;
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
      setError(errorMessage(failure, intl));
    } finally {
      submitting.current = false;
      setCode('');
      setBusy(false);
    }
  }

  function startOver() {
    setError(null);
    setCode('');
    cancelMfa();
  }

  const submitLabel = intl.formatMessage({ id: mfaRequired ? 'auth.verifyCode' : 'auth.signIn' });
  return (
    <SafeAreaView style={[styles.container, { backgroundColor: theme.background }]}>
      <KeyboardAvoidingView
        style={styles.container}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
          <View style={[styles.card, { backgroundColor: theme.backgroundElement }]}>
            <ThemedText type="smallBold" themeColor="primary" style={styles.brand}>
              {intl.formatMessage({ id: 'app.name' })}
            </ThemedText>
            <ThemedText type="subtitle" accessibilityRole="header">
              {intl.formatMessage({ id: mfaRequired ? 'auth.verifySignIn' : 'auth.signIn' })}
            </ThemedText>
            {!mfaRequired ? (
              <>
                <LabeledField
                  label={intl.formatMessage({ id: 'auth.server' })}
                  placeholder={SERVER_PLACEHOLDER}
                  autoCapitalize="none"
                  autoCorrect={false}
                  keyboardType="url"
                  value={server}
                  onChangeText={setServer}
                />
                <LabeledField
                  label={intl.formatMessage({ id: 'auth.email' })}
                  autoCapitalize="none"
                  autoComplete="email"
                  keyboardType="email-address"
                  returnKeyType="next"
                  submitBehavior="submit"
                  onSubmitEditing={() => passwordInput.current?.focus()}
                  value={email}
                  onChangeText={setEmail}
                />
                <LabeledField
                  label={intl.formatMessage({ id: 'auth.password' })}
                  ref={passwordInput}
                  secureTextEntry
                  returnKeyType="go"
                  onSubmitEditing={() => void submit()}
                  autoComplete="password"
                  value={password}
                  onChangeText={setPassword}
                />
              </>
            ) : (
              <LabeledField
                label={intl.formatMessage({ id: 'auth.authenticatorCode' })}
                keyboardType="number-pad"
                autoComplete="one-time-code"
                maxLength={6}
                value={code}
                onChangeText={setCode}
              />
            )}
            {error && <ThemedText accessibilityRole="alert">{error}</ThemedText>}
            <Pressable
              accessibilityRole="button"
              // The spinner replaces the text while busy; the button keeps its name for screen readers.
              accessibilityLabel={submitLabel}
              aria-busy={busy}
              aria-disabled={busy}
              style={[styles.submit, { backgroundColor: theme.primary }, busy && styles.disabled]}
              onPress={() => void submit()}
              disabled={busy}
            >
              {busy ? (
                <ActivityIndicator color={theme.onPrimary} />
              ) : (
                <ThemedText themeColor="onPrimary" style={styles.submitText}>
                  {submitLabel}
                </ThemedText>
              )}
            </Pressable>
            {mfaRequired && (
              <Pressable
                accessibilityRole="button"
                aria-disabled={busy}
                style={styles.back}
                onPress={startOver}
                disabled={busy}
              >
                <ThemedText type="smallBold">
                  {intl.formatMessage({ id: 'auth.startOver' })}
                </ThemedText>
              </Pressable>
            )}
          </View>
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

function errorMessage(failure: unknown, intl: IntlShape): string {
  if (failure instanceof InvalidServerUrlError) {
    return intl.formatMessage({
      id:
        failure.reason === 'insecure'
          ? 'auth.error.serverUrlInsecure'
          : 'auth.error.serverUrlFormat',
    });
  }
  // Not the credentials' fault: saying so would send the user hunting for a typo.
  if (failure instanceof NetworkError) return intl.formatMessage({ id: 'auth.error.network' });
  // One message for every other refusal: it never tells which of email, password or code was wrong.
  return intl.formatMessage({ id: 'auth.error.signIn' });
}

/** A visible label above the input; the same text is its accessible name. */
function LabeledField({
  label,
  ref,
  ...input
}: TextInputProps & { label: string; ref?: Ref<ComponentRef<typeof TextInput>> }) {
  const theme = useTheme();
  return (
    <View style={styles.labeled}>
      <ThemedText type="small" themeColor="textSecondary">
        {label}
      </ThemedText>
      <TextInput
        ref={ref}
        accessibilityLabel={label}
        placeholderTextColor={theme.textSecondary}
        style={[styles.field, { color: theme.text, borderColor: theme.textSecondary }]}
        {...input}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  content: { flexGrow: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  card: { width: '100%', maxWidth: 440, borderRadius: 20, padding: 28, gap: 18 },
  brand: { letterSpacing: 0.3 },
  labeled: { gap: 8 },
  field: { borderWidth: 1, minHeight: 48, padding: 12, borderRadius: 10 },
  submit: {
    minHeight: 48,
    borderRadius: 10,
    justifyContent: 'center',
    alignItems: 'center',
    marginTop: 8,
  },
  submitText: { fontSize: 16, fontWeight: '700' },
  disabled: { opacity: 0.5 },
  back: { minHeight: 44, justifyContent: 'center', alignItems: 'center' },
});

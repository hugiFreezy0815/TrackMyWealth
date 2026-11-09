import { Platform } from 'react-native';
import { resolveLocale } from '@/i18n';

const en = {
  signIn: 'Sign in',
  verifySignIn: 'Verify sign-in',
  verifyCode: 'Verify code',
  startOver: 'Start over',
  server: 'Server',
  email: 'Email',
  password: 'Password',
  authenticatorCode: 'Authenticator code',
  networkError: 'Could not reach the server. Check the server address and your connection.',
  signInError: 'Could not sign in. Check your credentials or verification code.',
};
const de: typeof en = {
  signIn: 'Anmelden',
  verifySignIn: 'Anmeldung bestätigen',
  verifyCode: 'Code bestätigen',
  startOver: 'Neu beginnen',
  server: 'Server',
  email: 'E-Mail',
  password: 'Passwort',
  authenticatorCode: 'Authenticator-Code',
  networkError: 'Der Server ist nicht erreichbar. Prüfe die Serveradresse und deine Verbindung.',
  signInError: 'Anmeldung fehlgeschlagen. Prüfe deine Zugangsdaten oder den Bestätigungscode.',
};
export function authCopy() {
  const deviceLocale =
    Platform.OS === 'web' && typeof navigator !== 'undefined'
      ? navigator.language
      : Intl.DateTimeFormat().resolvedOptions().locale;
  return resolveLocale(null, deviceLocale).startsWith('de') ? de : en;
}

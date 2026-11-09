import type { ClientReaction, ProblemReaction } from '@/api/problems';

/**
 * Temporary #488 presentation messages for a branch based on main.
 * #486 will replace this adapter with FormatJS catalogs; the keys follow the spec
 * (docs/ux/screens/README.md, e.g. `valueStatus.estimated`) so they move over unchanged.
 * No business figures are calculated here.
 */
export type UiLocale = 'en' | 'de-CH' | 'de-DE';

/** Problem codes with their own message; every other code falls back to its reaction's message. */
const PROBLEM_MESSAGE_CODES = [
  'UNAUTHENTICATED',
  'FORBIDDEN',
  'NOT_FOUND',
  'VALIDATION_FAILED',
  'VERSION_REQUIRED',
  'VERSION_CONFLICT',
  'ACCOUNT_ARCHIVED',
  'LOCKED',
  'RATE_LIMITED',
  'INTERNAL',
] as const;
type ProblemMessageCode = (typeof PROBLEM_MESSAGE_CODES)[number];

export type MessageKey =
  | 'amount.hidden'
  | 'amount.unknown'
  | 'amount.negative'
  | 'amount.positive'
  | 'amount.approximate'
  | 'valueStatus.estimated'
  | 'valueStatus.stale'
  | 'valueStatus.staleWithoutDate'
  | 'valueStatus.unreconciled'
  | 'valueStatus.unknown'
  | 'valueStatus.estimated.explanation'
  | 'valueStatus.stale.explanation'
  | 'valueStatus.unreconciled.explanation'
  | 'valueStatus.unknown.explanation'
  | 'valueStatus.explain'
  | 'valueStatus.dismiss'
  | `problem.${ProblemMessageCode}`
  | `problem.reaction.${ClientReaction}`
  | 'ui.retry'
  | 'ui.offline'
  | 'ui.offlineDetail'
  | 'ui.lastUpdated'
  | 'ui.error'
  | 'ui.empty'
  | 'ui.loading'
  | 'ui.close'
  | 'ui.cancel'
  | 'ui.continue'
  | 'ui.previous'
  | 'ui.confirm'
  | 'ui.conflict'
  | 'ui.conflictDetail'
  | 'ui.reviewAgain'
  | 'ui.discard'
  | 'ui.search'
  | 'ui.select'
  | 'ui.none'
  | 'ui.chooseDate'
  | 'ui.datePlaceholder'
  | 'ui.required'
  | 'ui.invalidAmount'
  | 'ui.invalidDate'
  | 'ui.change'
  | 'ui.details'
  | 'ui.unavailable'
  | 'ui.more'
  | 'ui.personalAssets';

const en: Record<MessageKey, string> = {
  'amount.hidden': 'Amount hidden',
  'amount.unknown': 'Value unknown',
  'amount.negative': 'minus {amount}',
  'amount.positive': 'plus {amount}',
  'amount.approximate': 'approximately {amount}',
  'valueStatus.estimated': 'Estimated',
  'valueStatus.stale': 'Price from {date}',
  'valueStatus.staleWithoutDate': 'Price outdated',
  'valueStatus.unreconciled': 'Differs from statement',
  'valueStatus.unknown': 'Value unknown',
  'valueStatus.estimated.explanation':
    'This value rests on an approximation, for example a rate or transactions before the opening balance.',
  'valueStatus.stale.explanation':
    'The newest statement, valuation or exchange rate behind this value is older than usual.',
  'valueStatus.unreconciled.explanation':
    'The ledger and the bank statement disagree. Review the difference.',
  'valueStatus.unknown.explanation': 'A reliable value is not available.',
  'valueStatus.explain': 'Explain value status',
  'valueStatus.dismiss': 'Dismiss explanation',
  'problem.UNAUTHENTICATED': 'Your session has ended. Sign in again.',
  'problem.FORBIDDEN': 'You are not allowed to do this.',
  'problem.NOT_FOUND': 'This item no longer exists.',
  'problem.VALIDATION_FAILED': 'Some entries are not valid. Check the marked fields.',
  'problem.VERSION_REQUIRED': 'This item has to be reloaded before it can be saved.',
  'problem.VERSION_CONFLICT': 'This item was changed meanwhile. Reload it and save again.',
  'problem.ACCOUNT_ARCHIVED': 'This account is archived and cannot be changed.',
  'problem.LOCKED': 'This item is being updated. Try again in a moment.',
  'problem.RATE_LIMITED': 'Too many requests. Try again in a moment.',
  'problem.INTERNAL': 'Something went wrong on the server.',
  'problem.reaction.FIX_INPUT': 'Some entries are not valid. Check them and try again.',
  'problem.reaction.SHOW': 'This is not possible right now.',
  'problem.reaction.RELOAD': 'The data has changed. Reload to see the latest state.',
  'problem.reaction.RELOAD_AND_RETRY': 'The data has changed. Reload it and try again.',
  'problem.reaction.RETRY': 'The request did not go through. Try again.',
  'problem.reaction.RETRY_LATER': 'The service is busy. Try again in a moment.',
  'problem.reaction.REAUTHENTICATE': 'Your session has ended. Sign in again.',
  'problem.reaction.REPORT': 'Something went wrong.',
  'ui.retry': 'Retry',
  'ui.offline': 'Offline',
  'ui.offlineDetail': 'You are offline. Last saved data remains available.',
  'ui.lastUpdated': 'Last updated {date}',
  'ui.error': 'Something went wrong.',
  'ui.empty': 'Nothing here yet',
  'ui.loading': 'Loading',
  'ui.close': 'Close',
  'ui.cancel': 'Cancel',
  'ui.continue': 'Continue',
  'ui.previous': 'Back',
  'ui.confirm': 'Confirm',
  'ui.conflict': 'This item was changed meanwhile',
  'ui.conflictDetail': 'Review the updated version and save again, or discard your edit.',
  'ui.reviewAgain': 'Review and save again',
  'ui.discard': 'Discard my edit',
  'ui.search': 'Search',
  'ui.select': 'Select',
  'ui.none': 'None',
  'ui.chooseDate': 'Date',
  'ui.datePlaceholder': 'YYYY-MM-DD',
  'ui.required': 'Required',
  'ui.invalidAmount': 'Enter a valid amount.',
  'ui.invalidDate': 'Enter a valid date (YYYY-MM-DD).',
  'ui.change': 'Change',
  'ui.details': 'Details',
  'ui.unavailable': 'Unavailable',
  'ui.more': 'More',
  'ui.personalAssets': 'Personal assets',
};
const de: Record<MessageKey, string> = {
  'amount.hidden': 'Betrag verborgen',
  'amount.unknown': 'Wert unbekannt',
  'amount.negative': 'minus {amount}',
  'amount.positive': 'plus {amount}',
  'amount.approximate': 'ungefähr {amount}',
  'valueStatus.estimated': 'Geschätzt',
  'valueStatus.stale': 'Kurs vom {date}',
  'valueStatus.staleWithoutDate': 'Kurs veraltet',
  'valueStatus.unreconciled': 'Weicht vom Auszug ab',
  'valueStatus.unknown': 'Wert unbekannt',
  'valueStatus.estimated.explanation':
    'Dieser Wert beruht auf einer Schätzung, etwa einem Kurs oder Buchungen vor dem Anfangssaldo.',
  'valueStatus.stale.explanation':
    'Der neueste Auszug, die Bewertung oder der Wechselkurs hinter diesem Wert ist älter als üblich.',
  'valueStatus.unreconciled.explanation':
    'Das Buch und der Kontoauszug stimmen nicht überein. Prüfe die Differenz.',
  'valueStatus.unknown.explanation': 'Es liegt kein verlässlicher Wert vor.',
  'valueStatus.explain': 'Wertstatus erklären',
  'valueStatus.dismiss': 'Erklärung schliessen',
  'problem.UNAUTHENTICATED': 'Deine Sitzung ist abgelaufen. Melde dich erneut an.',
  'problem.FORBIDDEN': 'Das darfst du nicht.',
  'problem.NOT_FOUND': 'Dieser Eintrag existiert nicht mehr.',
  'problem.VALIDATION_FAILED': 'Einige Angaben sind ungültig. Prüfe die markierten Felder.',
  'problem.VERSION_REQUIRED': 'Lade diesen Eintrag neu, bevor du ihn speicherst.',
  'problem.VERSION_CONFLICT':
    'Dieser Eintrag wurde inzwischen geändert. Lade ihn neu und speichere erneut.',
  'problem.ACCOUNT_ARCHIVED': 'Dieses Konto ist archiviert und kann nicht geändert werden.',
  'problem.LOCKED': 'Dieser Eintrag wird gerade aktualisiert. Versuche es gleich noch einmal.',
  'problem.RATE_LIMITED': 'Zu viele Anfragen. Versuche es gleich noch einmal.',
  'problem.INTERNAL': 'Auf dem Server ist etwas schiefgelaufen.',
  'problem.reaction.FIX_INPUT': 'Einige Angaben sind ungültig. Prüfe sie und versuche es erneut.',
  'problem.reaction.SHOW': 'Das ist gerade nicht möglich.',
  'problem.reaction.RELOAD': 'Die Daten haben sich geändert. Lade neu, um den aktuellen Stand zu sehen.',
  'problem.reaction.RELOAD_AND_RETRY':
    'Die Daten haben sich geändert. Lade sie neu und versuche es erneut.',
  'problem.reaction.RETRY': 'Die Anfrage ist nicht durchgekommen. Versuche es erneut.',
  'problem.reaction.RETRY_LATER': 'Der Dienst ist ausgelastet. Versuche es gleich noch einmal.',
  'problem.reaction.REAUTHENTICATE': 'Deine Sitzung ist abgelaufen. Melde dich erneut an.',
  'problem.reaction.REPORT': 'Etwas ist schiefgelaufen.',
  'ui.retry': 'Erneut versuchen',
  'ui.offline': 'Offline',
  'ui.offlineDetail': 'Du bist offline. Zuletzt gespeicherte Daten bleiben sichtbar.',
  'ui.lastUpdated': 'Zuletzt aktualisiert: {date}',
  'ui.error': 'Etwas ist schiefgelaufen.',
  'ui.empty': 'Hier gibt es noch nichts',
  'ui.loading': 'Lädt',
  'ui.close': 'Schliessen',
  'ui.cancel': 'Abbrechen',
  'ui.continue': 'Weiter',
  'ui.previous': 'Zurück',
  'ui.confirm': 'Bestätigen',
  'ui.conflict': 'Dieser Eintrag wurde inzwischen geändert',
  'ui.conflictDetail': 'Prüfe die neue Version und speichere erneut oder verwirf deine Änderung.',
  'ui.reviewAgain': 'Prüfen und erneut speichern',
  'ui.discard': 'Meine Änderung verwerfen',
  'ui.search': 'Suchen',
  'ui.select': 'Auswählen',
  'ui.none': 'Keine',
  'ui.chooseDate': 'Datum',
  'ui.datePlaceholder': 'JJJJ-MM-TT',
  'ui.required': 'Pflichtfeld',
  'ui.invalidAmount': 'Gib einen gültigen Betrag ein.',
  'ui.invalidDate': 'Gib ein gültiges Datum ein (JJJJ-MM-TT).',
  'ui.change': 'Änderung',
  'ui.details': 'Details',
  'ui.unavailable': 'Nicht verfügbar',
  'ui.more': 'Mehr',
  'ui.personalAssets': 'Privatvermögen',
};

export function uiLanguage(locale: string): 'en' | 'de' {
  return locale.toLowerCase().startsWith('de') ? 'de' : 'en';
}

export function uiMessage(
  key: MessageKey,
  locale: string = 'en',
  values?: Record<string, string>,
): string {
  const base = uiLanguage(locale) === 'de' ? de[key] : en[key];
  return base.replace(/\{(\w+)\}/g, (full, name: string) => values?.[name] ?? full);
}

export function currentUiLocale(): UiLocale {
  const language = Intl.DateTimeFormat().resolvedOptions().locale;
  if (language.toLowerCase().startsWith('de-ch')) return 'de-CH';
  if (language.toLowerCase().startsWith('de')) return 'de-DE';
  return 'en';
}

/**
 * Spec README §8: an error shows the message key of its problem `code`. A code without its own
 * message - or one this app version does not know - shows the message of its client reaction.
 */
export function problemMessageKey(problem: ProblemReaction): MessageKey {
  const code = problem.code;
  if (code && (PROBLEM_MESSAGE_CODES as readonly string[]).includes(code)) {
    return `problem.${code as ProblemMessageCode}`;
  }
  return `problem.reaction.${problem.reaction}`;
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Display a server date (`YYYY-MM-DD`) or date-time (ISO 8601) in the UI locale. A calendar
 * date is formatted in UTC so it never shifts by a day; anything unparseable is shown as sent.
 */
export function displayDate(value: string, locale: UiLocale): string {
  try {
    if (ISO_DATE.test(value)) {
      const [year, month, day] = value.split('-').map(Number);
      return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone: 'UTC' }).format(
        new Date(Date.UTC(year, month - 1, day)),
      );
    }
    const time = Date.parse(value);
    if (Number.isNaN(time)) return value;
    return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(
      new Date(time),
    );
  } catch {
    return value;
  }
}

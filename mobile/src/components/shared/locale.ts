/**
 * Temporary #488 presentation messages for a branch based on main.
 * #486 will replace this adapter with FormatJS catalogs; component keys stay stable.
 * No business figures are calculated here.
 */
export type UiLocale = 'en' | 'de-CH' | 'de-DE';
type MessageKey =
  | 'amount.hidden' | 'amount.unknown' | 'value.estimated' | 'value.stale'
  | 'value.unreconciled' | 'value.unknown' | 'value.explanation.estimated'
  | 'value.explanation.stale' | 'value.explanation.unreconciled'
  | 'value.explanation.unknown' | 'value.explain' | 'value.dismiss'
  | 'ui.retry' | 'ui.offline' | 'ui.offlineDetail' | 'ui.lastUpdated'
  | 'ui.error' | 'ui.empty' | 'ui.loading' | 'ui.close' | 'ui.cancel'
  | 'ui.continue' | 'ui.previous' | 'ui.confirm' | 'ui.conflict'
  | 'ui.conflictDetail' | 'ui.reviewAgain' | 'ui.discard'
  | 'ui.search' | 'ui.select' | 'ui.none' | 'ui.selected'
  | 'ui.chooseDate' | 'ui.required' | 'ui.invalidAmount' | 'ui.invalidDate'
  | 'ui.change' | 'ui.details' | 'ui.unavailable' | 'ui.more';
const en: Record<MessageKey, string> = {
  'amount.hidden': 'Amount hidden',
  'amount.unknown': 'Value unknown',
  'value.estimated': 'Estimated',
  'value.stale': 'Price from {date}',
  'value.unreconciled': 'Differs from statement',
  'value.unknown': 'Value unknown',
  'value.explanation.estimated': 'This value is based on an approximation, such as a rate or transactions before the opening balance.',
  'value.explanation.stale': 'The newest statement, valuation or exchange rate behind this value is older than usual.',
  'value.explanation.unreconciled': 'The ledger and the bank statement disagree. Review the difference.',
  'value.explanation.unknown': 'A reliable value is not available.',
  'value.explain': 'Explain value status',
  'value.dismiss': 'Dismiss explanation',
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
  'ui.selected': 'Selected',
  'ui.chooseDate': 'Date',
  'ui.required': 'Required',
  'ui.invalidAmount': 'Enter a valid amount.',
  'ui.invalidDate': 'Enter a valid date (YYYY-MM-DD).',
  'ui.change': 'Change',
  'ui.details': 'Details',
  'ui.unavailable': 'Unavailable',
  'ui.more': 'More',
};
const de: Record<MessageKey, string> = {
  'amount.hidden': 'Betrag verborgen',
  'amount.unknown': 'Wert unbekannt',
  'value.estimated': 'Geschätzt',
  'value.stale': 'Kurs vom {date}',
  'value.unreconciled': 'Weicht vom Auszug ab',
  'value.unknown': 'Wert unbekannt',
  'value.explanation.estimated': 'Dieser Wert basiert auf einer Schätzung, etwa einem Kurs oder Buchungen vor dem Anfangssaldo.',
  'value.explanation.stale': 'Der neueste Auszug, die Bewertung oder der Wechselkurs ist älter als üblich.',
  'value.explanation.unreconciled': 'Das Buch und der Kontoauszug stimmen nicht überein. Prüfe die Differenz.',
  'value.explanation.unknown': 'Es liegt kein verlässlicher Wert vor.',
  'value.explain': 'Wertstatus erklären',
  'value.dismiss': 'Erklärung schliessen',
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
  'ui.selected': 'Ausgewählt',
  'ui.chooseDate': 'Datum',
  'ui.required': 'Pflichtfeld',
  'ui.invalidAmount': 'Gib einen gültigen Betrag ein.',
  'ui.invalidDate': 'Gib ein gültiges Datum ein (JJJJ-MM-TT).',
  'ui.change': 'Änderung',
  'ui.details': 'Details',
  'ui.unavailable': 'Nicht verfügbar',
  'ui.more': 'Mehr',
};
export function uiLanguage(locale: string): 'en' | 'de' {
  return locale.toLowerCase().startsWith('de') ? 'de' : 'en';
}
export function uiMessage(key: MessageKey, locale: string = 'en', values?: Record<string, string>): string {
  const base = uiLanguage(locale) === 'de' ? de[key] : en[key];
  return base.replace(/\{(\w+)\}/g, (full, name: string) => values?.[name] ?? full);
}
export function currentUiLocale(): UiLocale {
  const language = Intl.DateTimeFormat().resolvedOptions().locale;
  if (language.toLowerCase().startsWith('de-ch')) return 'de-CH';
  if (language.toLowerCase().startsWith('de')) return 'de-DE';
  return 'en';
}

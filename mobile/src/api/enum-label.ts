/**
 * #452: response enums are open at runtime: a self-hosted backend may be newer than the app.
 * The generated union stays strict for known values; its display boundary accepts new strings.
 * Unknown values are never interpreted as one of the known statuses or types.
 */
export type DisplayEnum<K extends string> = K | (string & {});

export type DisplayLanguage = 'en' | 'de';

export const UNKNOWN_ENUM_LABELS: Readonly<Record<DisplayLanguage, string>> = {
  en: 'Other',
  de: 'Sonstiges',
};

const warned = new Set<string>();

/**
 * Use only own keys, so an unexpected value such as "toString" cannot resolve to a
 * prototype member. Warn once per distinct value during development, never in production.
 */
export function labelFor<K extends string>(
  labels: Readonly<Record<K, string>>,
  value: DisplayEnum<NoInfer<K>> | null | undefined,
  fallback: string,
): string {
  if (typeof value === 'string' && Object.prototype.hasOwnProperty.call(labels, value)) {
    return labels[value as K];
  }
  if (typeof value === 'string' && process.env.NODE_ENV !== 'production' && !warned.has(value)) {
    warned.add(value);
    console.warn('Unknown API enum value:', value);
  }
  return fallback;
}

type SetDefaultTimeZone = (timeZone: string) => void;

/**
 * Hands the device's time zone to the polyfilled DateTimeFormat. Its zone data has no aliases
 * such as "GMT" (what iOS reports for UTC), and it throws a RangeError for an unknown name; at
 * startup that would crash the app before the first screen. So try the name, then its Etc/ form,
 * and otherwise keep the polyfill's UTC default. Returns the zone that was applied, if any.
 */
export function applyDeviceTimeZone(
  setDefaultTimeZone: SetDefaultTimeZone | undefined,
  deviceTimeZone: string,
): string | undefined {
  if (!setDefaultTimeZone) return undefined; // the engine's own DateTimeFormat is in use
  for (const candidate of [deviceTimeZone, `Etc/${deviceTimeZone}`]) {
    try {
      setDefaultTimeZone(candidate);
      return candidate;
    } catch {
      // not in the polyfill's zone data: try the next spelling
    }
  }
  return undefined;
}

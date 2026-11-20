import Constants from 'expo-constants';

/**
 * US-29-16 (#576): the header every API request carries with this build's version, so a server can
 * refuse a build it no longer serves (426 APP_UPDATE_REQUIRED, see update-required.ts).
 */
export const APP_VERSION_HEADER = 'X-App-Version';

// The server's accepted form (AppVersion.PATTERN in the backend): MAJOR.MINOR.PATCH, plain numbers.
const VERSION_FORM = /^(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})$/;

/**
 * `version` when the server accepts it, else undefined: a build whose app.json version the server
 * would reject as malformed (400) sends no header rather than failing every request.
 */
export function acceptedAppVersion(version: string | null | undefined): string | undefined {
  return version != null && VERSION_FORM.test(version) ? version : undefined;
}

/** This build's version from app.json (`expo.version`), when it is MAJOR.MINOR.PATCH. */
export const APP_VERSION = acceptedAppVersion(Constants.expoConfig?.version);

/** The version header for a request, or none when the version is unknown. */
export function appVersionHeaders(version: string | undefined): Record<string, string> {
  return version ? { [APP_VERSION_HEADER]: version } : {};
}

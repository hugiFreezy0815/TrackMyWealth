import appJson from '../../app.json';
import { APP_VERSION, acceptedAppVersion, appVersionHeaders } from './app-version';

jest.mock('expo-constants', () => ({
  __esModule: true,
  default: { expoConfig: { version: '4.5.6' } },
}));

describe('app version (#576)', () => {
  it("is this build's expo config version", () => {
    expect(APP_VERSION).toBe('4.5.6');
  });

  it('is MAJOR.MINOR.PATCH in app.json, or the build would send no version at all', () => {
    expect(acceptedAppVersion(appJson.expo.version)).toBe(appJson.expo.version);
  });

  it.each(['1.0.0', '0.0.1', '12.34.56', '999999.0.0'])('accepts %s', (version) => {
    expect(acceptedAppVersion(version)).toBe(version);
  });

  it.each(['', '1', '1.0', '1.0.0-beta', '01.0.0', ' 1.0.0', '1000000.0.0', null, undefined])(
    'sends no header rather than one the server rejects: %p',
    (version) => {
      expect(acceptedAppVersion(version)).toBeUndefined();
    },
  );

  it('builds the header only for a known version', () => {
    expect(appVersionHeaders('1.2.3')).toEqual({ 'X-App-Version': '1.2.3' });
    expect(appVersionHeaders(undefined)).toEqual({});
  });
});

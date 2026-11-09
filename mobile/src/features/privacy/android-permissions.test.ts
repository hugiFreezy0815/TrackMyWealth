import appConfig from '../../../app.json';

// expo-screen-capture (app-switcher protection) declares Android permissions in its manifest.
// app.json blocks the ones the app does not need; blocking one the module needs crashes the app.
const blocked: string[] = appConfig.expo.android.blockedPermissions;

describe('Android permissions for screen protection', () => {
  it('keeps DETECT_SCREEN_CAPTURE, which Android 14+ needs at start-up', () => {
    // The module registers Activity.ScreenCaptureCallback in OnCreate on API 34+; without this
    // install-time permission the app dies at launch (seen in Mobile Native CI on API 35).
    expect(blocked).not.toContain('android.permission.DETECT_SCREEN_CAPTURE');
  });

  it('blocks the media permissions of the unused screenshot listener (least privilege)', () => {
    // Below API 34 the module only observes MediaStore and checks these before reading anything.
    expect(blocked).toEqual(
      expect.arrayContaining([
        'android.permission.READ_EXTERNAL_STORAGE',
        'android.permission.READ_MEDIA_IMAGES',
      ]),
    );
  });
});

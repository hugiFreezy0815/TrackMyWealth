import { useIntl } from 'react-intl';
import { Platform, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { APP_VERSION } from '@/api/app-version';
import { ActionButton } from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

/**
 * US-29-16 (#576): the server no longer serves this app version (426 APP_UPDATE_REQUIRED). Replaces
 * the whole app, signed in or not, because every request would fail the same way. The stored
 * session is kept, so the updated app opens signed in.
 *
 * Native asks for a store update and offers to try again (for a server whose minimum was lowered
 * meanwhile). The web build is updated by loading the page again, so its button reloads.
 */
export function UpdateRequiredScreen({
  onRetry,
  version = APP_VERSION,
  platform = Platform.OS,
  reload = () => globalThis.location?.reload(),
}: {
  onRetry: () => void;
  version?: string;
  platform?: typeof Platform.OS;
  reload?: () => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const web = platform === 'web';
  return (
    <SafeAreaView
      role="main"
      style={{ flex: 1, justifyContent: 'center', backgroundColor: theme.background }}
    >
      <View
        accessibilityRole="alert"
        style={{
          padding: Spacing.four,
          gap: Spacing.three,
          alignItems: 'center',
          alignSelf: 'center',
          maxWidth: 480,
        }}
      >
        <Text
          accessibilityRole="header"
          style={{ ...TypeScale.headingMedium, color: theme.textPrimary, textAlign: 'center' }}
        >
          {intl.formatMessage({ id: 'updateRequired.title' })}
        </Text>
        <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary, textAlign: 'center' }}>
          {intl.formatMessage({ id: web ? 'updateRequired.body.web' : 'updateRequired.body' })}
        </Text>
        {version ? (
          <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
            {intl.formatMessage({ id: 'updateRequired.version' }, { version })}
          </Text>
        ) : null}
        <ActionButton
          label={intl.formatMessage({ id: web ? 'updateRequired.reload' : 'error.retry' })}
          onPress={web ? reload : onRetry}
        />
      </View>
    </SafeAreaView>
  );
}

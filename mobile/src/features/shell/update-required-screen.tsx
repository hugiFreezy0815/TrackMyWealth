import { Image } from 'expo-image';
import { useRef, useState } from 'react';
import { useIntl } from 'react-intl';
import {
  ActivityIndicator,
  Platform,
  ScrollView,
  Text,
  View,
  useWindowDimensions,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { APP_VERSION } from '@/api/app-version';
import { NetworkError } from '@/api/client';
import { ActionButton } from '@/components/shared';
import { Layout, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

/**
 * Blocking HTTP 426 state. The root layout owns the session and replaces the app with this view;
 * retrying never clears stored credentials.
 *
 * M24 native / W14 web (#648, #649). Use a scrollable full-viewport canvas so dynamic type,
 * web zoom and short devices never clip the instruction or primary action.
 */
export function UpdateRequiredScreen({
  onRetry,
  version = APP_VERSION,
  platform = Platform.OS,
  reload = () => globalThis.location?.reload(),
}: {
  onRetry: () => void | Promise<void>;
  version?: string;
  platform?: typeof Platform.OS;
  reload?: () => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const { width } = useWindowDimensions();
  const web = platform === 'web';
  const phone = width <= Layout.phoneMax;
  const [retrying, setRetrying] = useState(false);
  const [retryError, setRetryError] = useState<string | null>(null);
  const retryInFlight = useRef(false);

  const handleAction = () => {
    if (retryInFlight.current) return;
    if (web) {
      reload();
      return;
    }
    retryInFlight.current = true;
    setRetrying(true);
    setRetryError(null);
    void (async () => {
      try {
        await onRetry();
      } catch (error) {
        if (error instanceof NetworkError) {
          setRetryError('updateRequired.networkError');
        } else {
          setRetryError('updateRequired.retryError');
        }
      } finally {
        retryInFlight.current = false;
        setRetrying(false);
      }
    })();
  };

  return (
    <SafeAreaView role="main" style={{ flex: 1, backgroundColor: theme.bgCanvas }}>
      <ScrollView
        style={{ flex: 1 }}
        contentContainerStyle={{
          flexGrow: 1,
          justifyContent: 'center',
          alignItems: 'center',
          padding: Spacing.four,
        }}
      >
        <View
          accessibilityRole="alert"
          style={{
            width: '100%',
            maxWidth: 480,
            alignItems: 'center',
            gap: Spacing.three,
          }}
        >
          <Image
            source={require('@/assets/images/icon.png')}
            style={{ width: 48, height: 48 }}
            accessible={false}
          />
          <Text
            accessibilityRole="header"
            style={{
              ...TypeScale.headingMedium,
              color: theme.textPrimary,
              textAlign: 'center',
              alignSelf: 'stretch',
            }}
          >
            {intl.formatMessage({ id: 'updateRequired.title' })}
          </Text>
          <Text
            style={{
              ...TypeScale.bodyMedium,
              color: theme.textSecondary,
              textAlign: 'center',
              alignSelf: 'stretch',
            }}
          >
            {intl.formatMessage({ id: web ? 'updateRequired.body.web' : 'updateRequired.body' })}
          </Text>
          {version ? (
            <Text
              style={{
                ...TypeScale.caption,
                color: theme.textSecondary,
                textAlign: 'center',
                alignSelf: 'stretch',
              }}
            >
              {intl.formatMessage({ id: 'updateRequired.version' }, { version })}
            </Text>
          ) : null}
          {retryError ? (
            <Text
              accessibilityRole="alert"
              style={{
                ...TypeScale.bodyMedium,
                color: theme.statusLoss,
                textAlign: 'center',
                alignSelf: 'stretch',
              }}
            >
              {intl.formatMessage({ id: retryError })}
            </Text>
          ) : null}
          <View style={{ width: phone ? '100%' : 240, maxWidth: '100%' }}>
            <ActionButton
              label={intl.formatMessage({ id: web ? 'updateRequired.reload' : 'error.retry' })}
              onPress={handleAction}
              disabled={retrying}
              minHeight={48}
            />
            {retrying ? (
              <ActivityIndicator
                accessibilityLabel={intl.formatMessage({ id: 'updateRequired.retrying' })}
                color={theme.brandPrimary}
                style={{ marginTop: Spacing.two }}
              />
            ) : null}
          </View>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

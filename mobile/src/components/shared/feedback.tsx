import { type ReactNode } from 'react';
import { ActivityIndicator, Modal, Pressable, Text, View } from 'react-native';

import { ApiError } from '@/api/client';
import { problemReaction } from '@/api/problems';
import { Radii, Spacing } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

import { currentUiLocale, uiMessage, type UiLocale } from './locale';

export function EmptyState({
  title,
  description,
  actionLabel,
  onAction,
  locale = currentUiLocale(),
}: {
  title?: string;
  description?: string;
  actionLabel?: string;
  onAction?: () => void;
  locale?: UiLocale;
}) {
  const theme = useTheme();
  return (
    <View style={{ padding: Spacing.four, gap: Spacing.two, alignItems: 'center' }}>
      <Text
        accessibilityRole="header"
        style={{ fontSize: 20, fontWeight: '600', color: theme.textPrimary }}
      >
        {title ?? uiMessage('ui.empty', locale)}
      </Text>
      {description ? (
        <Text style={{ color: theme.textSecondary, textAlign: 'center' }}>{description}</Text>
      ) : null}
      {actionLabel && onAction ? <ActionButton label={actionLabel} onPress={onAction} /> : null}
    </View>
  );
}

export function ActionButton({
  label,
  onPress,
  disabled = false,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
}) {
  const theme = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={{
        minHeight: 44,
        justifyContent: 'center',
        alignItems: 'center',
        borderRadius: Radii.control,
        paddingHorizontal: Spacing.three,
        backgroundColor: theme.brandPrimary,
        opacity: disabled ? 0.5 : 1,
      }}
    >
      <Text style={{ fontWeight: '600', color: theme.textOnBrand }}>{label}</Text>
    </Pressable>
  );
}
export function ErrorBanner({
  error,
  message,
  onRetry,
  locale = currentUiLocale(),
}: {
  error?: unknown;
  message?: string;
  onRetry?: () => void;
  locale?: UiLocale;
}) {
  const theme = useTheme();
  const reaction = error instanceof ApiError ? problemReaction(error) : null;
  // Display an explicit supplied, user-safe message, never leak a server's raw error.
  // Unknown problem codes have a safe generic message and status-based reaction.
  const showRetry =
    onRetry != null && (!reaction || !['REAUTHENTICATE', 'SHOW'].includes(reaction.reaction));
  return (
    <View
      accessibilityRole="alert"
      style={{
        padding: Spacing.three,
        borderRadius: Radii.control,
        borderLeftWidth: 4,
        borderLeftColor: theme.statusLoss,
        backgroundColor: theme.statusLossSubtle,
        gap: 8,
      }}
    >
      <Text style={{ color: theme.textPrimary }}>{message ?? uiMessage('ui.error', locale)}</Text>
      {reaction?.correlationId ? (
        <Text style={{ fontSize: 12, color: theme.textSecondary }}>{reaction.correlationId}</Text>
      ) : null}
      {showRetry ? <ActionButton onPress={onRetry} label={uiMessage('ui.retry', locale)} /> : null}
    </View>
  );
}
export function OfflineBanner({
  lastUpdated,
  locale = currentUiLocale(),
}: {
  lastUpdated?: string;
  locale?: UiLocale;
}) {
  const theme = useTheme();
  return (
    <View
      accessibilityRole="alert"
      style={{
        borderLeftColor: theme.statusWarning,
        borderLeftWidth: 4,
        padding: Spacing.compact,
        backgroundColor: theme.statusWarningSubtle,
        borderRadius: Radii.control,
        gap: 4,
      }}
    >
      <Text style={{ color: theme.textPrimary, fontWeight: '600' }}>
        {uiMessage('ui.offline', locale)}
      </Text>
      <Text style={{ color: theme.textPrimary }}>{uiMessage('ui.offlineDetail', locale)}</Text>
      {lastUpdated ? (
        <Text style={{ color: theme.textSecondary }}>
          {uiMessage('ui.lastUpdated', locale, { date: lastUpdated })}
        </Text>
      ) : null}
    </View>
  );
}
export function LoadingSkeleton({
  width = '100%',
  height = 16,
  count = 3,
  locale = currentUiLocale(),
}: {
  width?: number | string;
  height?: number;
  count?: number;
  locale?: UiLocale;
}) {
  const theme = useTheme();
  return (
    <View
      accessibilityLabel={uiMessage('ui.loading', locale)}
      accessibilityState={{ busy: true }}
      style={{ gap: 12 }}
    >
      {Array.from({ length: Math.max(0, Math.min(20, count)) }, (_, index) => (
        <View
          key={index}
          style={{
            width: width as number,
            height,
            backgroundColor: theme.bgSubtle,
            borderRadius: Radii.control,
          }}
        />
      ))}
    </View>
  );
}
export function Dialog({
  title,
  visible,
  onClose,
  children,
  locale = currentUiLocale(),
}: {
  title: string;
  visible: boolean;
  onClose: () => void;
  children: ReactNode;
  locale?: UiLocale;
}) {
  const theme = useTheme();
  return (
    <Modal transparent visible={visible} animationType="fade" onRequestClose={onClose}>
      <View
        style={{ flex: 1, justifyContent: 'center', backgroundColor: '#00000099', padding: 20 }}
      >
        <View
          style={{
            backgroundColor: theme.bgSurface,
            borderRadius: Radii.card,
            padding: Spacing.four,
            gap: 16,
          }}
        >
          <Text
            accessibilityRole="header"
            style={{ fontWeight: '600', fontSize: 20, color: theme.textPrimary }}
          >
            {title}
          </Text>
          {children}
          <Pressable
            accessibilityRole="button"
            onPress={onClose}
            style={{ minHeight: 44, justifyContent: 'center' }}
          >
            <Text style={{ color: theme.brandLink }}>{uiMessage('ui.close', locale)}</Text>
          </Pressable>
        </View>
      </View>
    </Modal>
  );
}
/** Native and web both use an accessible modal; #487 owns route-level presentation. */
export const BottomSheet = Dialog;

export function ConflictDialog({
  visible,
  onReview,
  onDiscard,
  onClose,
  locale = currentUiLocale(),
}: {
  visible: boolean;
  onReview: () => void;
  onDiscard: () => void;
  onClose: () => void;
  locale?: UiLocale;
}) {
  const theme = useTheme();
  return (
    <Dialog
      visible={visible}
      onClose={onClose}
      title={uiMessage('ui.conflict', locale)}
      locale={locale}
    >
      <Text style={{ color: theme.textSecondary }}>{uiMessage('ui.conflictDetail', locale)}</Text>
      <ActionButton label={uiMessage('ui.reviewAgain', locale)} onPress={onReview} />
      <ActionButton label={uiMessage('ui.discard', locale)} onPress={onDiscard} />
    </Dialog>
  );
}
export function RefreshingIndicator({ active }: { active: boolean }) {
  const theme = useTheme();
  return active ? <ActivityIndicator color={theme.brandPrimary} /> : null;
}

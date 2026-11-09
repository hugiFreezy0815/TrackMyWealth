import { type ReactNode } from 'react';
import {
  ActivityIndicator,
  Modal,
  Pressable,
  Text,
  View,
  type DimensionValue,
} from 'react-native';

import { ApiError } from '@/api/client';
import { problemReaction, type ClientReaction } from '@/api/problems';
import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

import {
  currentUiLocale,
  displayDate,
  problemMessageKey,
  uiMessage,
  type UiLocale,
} from './locale';

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
        style={{ ...TypeScale.headingMedium, color: theme.textPrimary }}
      >
        {title ?? uiMessage('ui.empty', locale)}
      </Text>
      {description ? (
        <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary, textAlign: 'center' }}>
          {description}
        </Text>
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
      <Text style={{ ...TypeScale.labelMedium, fontWeight: '600', color: theme.textOnBrand }}>
        {label}
      </Text>
    </Pressable>
  );
}
const NO_RETRY_REACTIONS: readonly ClientReaction[] = ['REAUTHENTICATE', 'SHOW', 'FIX_INPUT'];

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
  // Spec README §8: the message key of the problem code (or of its reaction for a code without
  // its own message or unknown to this version). A server's raw title/detail is never shown.
  const text =
    message ?? (reaction ? uiMessage(problemMessageKey(reaction), locale) : uiMessage('ui.error', locale));
  // Retrying cannot help when the member must sign in again, change the input, or the same
  // request is bound to fail again.
  const showRetry =
    onRetry != null &&
    (!reaction || !NO_RETRY_REACTIONS.includes(reaction.reaction));
  return (
    <View
      accessibilityRole="alert"
      style={{
        padding: Spacing.three,
        borderRadius: Radii.control,
        borderLeftWidth: 4,
        borderLeftColor: theme.statusLoss,
        backgroundColor: theme.statusLossSubtle,
        gap: Spacing.two,
      }}
    >
      <Text style={{ ...TypeScale.bodyMedium, color: theme.textPrimary }}>{text}</Text>
      {reaction?.correlationId ? (
        <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
          {reaction.correlationId}
        </Text>
      ) : null}
      {showRetry ? <ActionButton onPress={onRetry} label={uiMessage('ui.retry', locale)} /> : null}
    </View>
  );
}
export function OfflineBanner({
  lastUpdated,
  locale = currentUiLocale(),
}: {
  /** ISO date or date-time of the last successful load. */
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
        gap: Spacing.one,
      }}
    >
      <Text style={{ ...TypeScale.labelMedium, fontWeight: '600', color: theme.textPrimary }}>
        {uiMessage('ui.offline', locale)}
      </Text>
      <Text style={{ ...TypeScale.bodyMedium, color: theme.textPrimary }}>
        {uiMessage('ui.offlineDetail', locale)}
      </Text>
      {lastUpdated ? (
        <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
          {uiMessage('ui.lastUpdated', locale, { date: displayDate(lastUpdated, locale) })}
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
  width?: DimensionValue;
  height?: number;
  count?: number;
  locale?: UiLocale;
}) {
  const theme = useTheme();
  return (
    <View
      accessibilityLabel={uiMessage('ui.loading', locale)}
      accessibilityState={{ busy: true }}
      style={{ gap: Spacing.compact }}
    >
      {Array.from({ length: Math.max(0, Math.min(20, count)) }, (_, index) => (
        <View
          key={index}
          style={{
            width,
            height,
            backgroundColor: theme.bgSubtle,
            borderRadius: Radii.control,
          }}
        />
      ))}
    </View>
  );
}
type DialogProps = {
  title: string;
  visible: boolean;
  onClose: () => void;
  children: ReactNode;
  locale?: UiLocale;
};

export function Dialog({
  title,
  visible,
  onClose,
  children,
  placement = 'center',
  locale = currentUiLocale(),
}: DialogProps & { placement?: 'center' | 'bottom' }) {
  const theme = useTheme();
  const bottom = placement === 'bottom';
  return (
    <Modal
      transparent
      visible={visible}
      animationType={bottom ? 'slide' : 'fade'}
      onRequestClose={onClose}
    >
      <View
        style={{
          flex: 1,
          justifyContent: bottom ? 'flex-end' : 'center',
          backgroundColor: theme.bgOverlay,
          padding: bottom ? 0 : Spacing.four,
        }}
      >
        <View
          accessibilityViewIsModal
          style={{
            backgroundColor: theme.bgSurface,
            borderRadius: bottom ? 0 : Radii.card,
            borderTopLeftRadius: Radii.card,
            borderTopRightRadius: Radii.card,
            padding: Spacing.four,
            gap: Spacing.three,
          }}
        >
          <Text
            accessibilityRole="header"
            style={{ ...TypeScale.headingMedium, color: theme.textPrimary }}
          >
            {title}
          </Text>
          {children}
          <Pressable
            accessibilityRole="button"
            onPress={onClose}
            style={{ minHeight: 44, justifyContent: 'center' }}
          >
            <Text style={{ ...TypeScale.labelMedium, color: theme.brandLink }}>
              {uiMessage('ui.close', locale)}
            </Text>
          </Pressable>
        </View>
      </View>
    </Modal>
  );
}
/** A modal sheet anchored to the bottom edge; #487 owns route-level presentation. */
export function BottomSheet(props: DialogProps) {
  return <Dialog {...props} placement="bottom" />;
}

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
      <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
        {uiMessage('ui.conflictDetail', locale)}
      </Text>
      <ActionButton label={uiMessage('ui.reviewAgain', locale)} onPress={onReview} />
      <ActionButton label={uiMessage('ui.discard', locale)} onPress={onDiscard} />
    </Dialog>
  );
}
export function RefreshingIndicator({ active }: { active: boolean }) {
  const theme = useTheme();
  return active ? <ActivityIndicator color={theme.brandPrimary} /> : null;
}

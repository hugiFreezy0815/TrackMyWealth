import { type ReactNode } from 'react';
import { useIntl } from 'react-intl';
import { ActivityIndicator, Modal, Pressable, Text, View, type DimensionValue } from 'react-native';

import { ApiError } from '@/api/client';
import { problemReaction, type ClientReaction, type ProblemReaction } from '@/api/problems';
import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { formatDate, formatDateTime } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';

export function EmptyState({
  title,
  description,
  actionLabel,
  onAction,
}: {
  title?: string;
  description?: string;
  actionLabel?: string;
  onAction?: () => void;
}) {
  const theme = useTheme();
  const intl = useIntl();
  return (
    <View style={{ padding: Spacing.four, gap: Spacing.two, alignItems: 'center' }}>
      <Text
        accessibilityRole="header"
        style={{ ...TypeScale.headingMedium, color: theme.textPrimary }}
      >
        {title ?? intl.formatMessage({ id: 'ui.empty' })}
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
/** Problem codes with their own catalogue message (`problem.<CODE>`). */
const PROBLEM_MESSAGE_CODES: readonly string[] = [
  'UNAUTHENTICATED',
  'FORBIDDEN',
  'NOT_FOUND',
  'VALIDATION_FAILED',
  'VERSION_REQUIRED',
  'VERSION_CONFLICT',
  'ACCOUNT_ARCHIVED',
  'LOCKED',
  'RATE_LIMITED',
  'INTERNAL',
];

/**
 * Spec README §8: an error shows the message key of its problem `code`. A code without its own
 * message - or one this app version does not know - shows the message of its client reaction.
 */
export function problemMessageKey(problem: Pick<ProblemReaction, 'code' | 'reaction'>): string {
  return problem.code && PROBLEM_MESSAGE_CODES.includes(problem.code)
    ? `problem.${problem.code}`
    : `problem.reaction.${problem.reaction}`;
}

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

const NO_RETRY_REACTIONS: readonly ClientReaction[] = ['REAUTHENTICATE', 'SHOW', 'FIX_INPUT'];

export function ErrorBanner({
  error,
  message,
  onRetry,
}: {
  error?: unknown;
  message?: string;
  onRetry?: () => void;
}) {
  const theme = useTheme();
  const intl = useIntl();
  const reaction = error instanceof ApiError ? problemReaction(error) : null;
  // Spec README §8: the message key of the problem code (or of its reaction for a code without
  // its own message or unknown to this version). A server's raw title/detail is never shown.
  const text =
    message ?? intl.formatMessage({ id: reaction ? problemMessageKey(reaction) : 'error.generic' });
  // Retrying cannot help when the member must sign in again, change the input, or the same
  // request is bound to fail again.
  const showRetry =
    onRetry != null && (!reaction || !NO_RETRY_REACTIONS.includes(reaction.reaction));
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
      {showRetry ? (
        <ActionButton onPress={onRetry} label={intl.formatMessage({ id: 'error.retry' })} />
      ) : null}
    </View>
  );
}
export function OfflineBanner({
  lastUpdated,
}: {
  /** ISO date or date-time of the last successful load. */
  lastUpdated?: string;
}) {
  const theme = useTheme();
  const intl = useIntl();
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
        {intl.formatMessage({ id: 'ui.offline' })}
      </Text>
      <Text style={{ ...TypeScale.bodyMedium, color: theme.textPrimary }}>
        {intl.formatMessage({ id: 'error.offline' })}
      </Text>
      {lastUpdated ? (
        <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
          {intl.formatMessage(
            { id: 'ui.lastUpdated' },
            {
              date: DATE_ONLY.test(lastUpdated)
                ? formatDate(lastUpdated, intl.locale)
                : formatDateTime(lastUpdated, intl.locale),
            },
          )}
        </Text>
      ) : null}
    </View>
  );
}
export function LoadingSkeleton({
  width = '100%',
  height = 16,
  count = 3,
}: {
  width?: DimensionValue;
  height?: number;
  count?: number;
}) {
  const theme = useTheme();
  const intl = useIntl();
  return (
    <View
      accessibilityLabel={intl.formatMessage({ id: 'ui.loading' })}
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
  /** The close button's text; "Close" by default. */
  closeLabel?: string;
};

export function Dialog({
  title,
  visible,
  onClose,
  children,
  closeLabel,
  placement = 'center',
}: DialogProps & { placement?: 'center' | 'bottom' }) {
  const theme = useTheme();
  const intl = useIntl();
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
              {closeLabel ?? intl.formatMessage({ id: 'ui.close' })}
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
}: {
  visible: boolean;
  onReview: () => void;
  onDiscard: () => void;
  onClose: () => void;
}) {
  const theme = useTheme();
  const intl = useIntl();
  return (
    <Dialog
      visible={visible}
      onClose={onClose}
      title={intl.formatMessage({ id: 'conflict.title' })}
    >
      <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
        {intl.formatMessage({ id: 'conflict.detail' })}
      </Text>
      <ActionButton label={intl.formatMessage({ id: 'conflict.review' })} onPress={onReview} />
      <ActionButton label={intl.formatMessage({ id: 'conflict.discard' })} onPress={onDiscard} />
    </Dialog>
  );
}
export function RefreshingIndicator({ active }: { active: boolean }) {
  const theme = useTheme();
  return active ? <ActivityIndicator color={theme.brandPrimary} /> : null;
}

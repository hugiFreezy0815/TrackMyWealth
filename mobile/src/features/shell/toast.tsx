import { useEffect, useSyncExternalStore } from 'react';
import { useIntl } from 'react-intl';
import { AccessibilityInfo, Platform, Pressable, Text } from 'react-native';

import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

/** How long a toast stays: long enough to read two short lines (WCAG 2.2.1 allows dismissing). */
export const TOAST_DURATION_MS = 5000;

type Toast = { id: number; messageId: string; values?: Record<string, string> };

let current: Toast | null = null;
let nextId = 1;
const listeners = new Set<() => void>();
const notify = () => listeners.forEach((listener) => listener());

/**
 * Shows a short confirmation above the screen, e.g. "Account added" after a flow that navigates
 * away (S6). Takes a catalogue message id, so the text follows the app language. A newer toast
 * replaces the current one.
 */
export function showToast(messageId: string, values?: Record<string, string>): void {
  current = { id: nextId++, messageId, values };
  notify();
}

export function dismissToast(): void {
  current = null;
  notify();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Renders the current toast; mounted once in the signed-in shell. Tap to dismiss. */
export function ToastHost() {
  const toast = useSyncExternalStore(
    subscribe,
    () => current,
    () => null,
  );
  const intl = useIntl();
  const theme = useTheme();
  const text = toast ? intl.formatMessage({ id: toast.messageId }, toast.values) : null;

  useEffect(() => {
    if (!toast || !text) return;
    // iOS has no live regions; Android and web announce the polite region below themselves.
    if (Platform.OS === 'ios') AccessibilityInfo.announceForAccessibility(text);
    const timer = setTimeout(() => {
      if (current?.id === toast.id) dismissToast();
    }, TOAST_DURATION_MS);
    return () => clearTimeout(timer);
  }, [toast, text]);

  if (!toast || !text) return null;
  return (
    <Pressable
      testID="toast"
      role="status"
      accessibilityLiveRegion="polite"
      accessibilityLabel={text}
      accessibilityHint={intl.formatMessage({ id: 'toast.dismiss' })}
      onPress={dismissToast}
      style={{
        // Bottom of the content area, above the phone tab bar: never over the screen's header.
        position: 'absolute',
        bottom: Spacing.three,
        alignSelf: 'center',
        maxWidth: 480,
        marginHorizontal: Spacing.three,
        paddingVertical: Spacing.two,
        paddingHorizontal: Spacing.three,
        minHeight: 44,
        justifyContent: 'center',
        borderRadius: Radii.control,
        backgroundColor: theme.bgInverse,
        zIndex: 10,
      }}
    >
      <Text style={{ ...TypeScale.labelMedium, color: theme.bgSurface }}>{text}</Text>
    </Pressable>
  );
}

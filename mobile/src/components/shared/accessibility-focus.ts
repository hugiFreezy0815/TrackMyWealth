import { AccessibilityInfo, Platform } from 'react-native';

type HostInstance = Parameters<typeof AccessibilityInfo.sendAccessibilityEvent>[0];

/**
 * Moves the screen reader (native) or keyboard (web) focus to `target`, e.g. the heading of a
 * panel that just opened. On web the element needs `tabIndex={-1}` to accept focus.
 */
export function moveAccessibilityFocus(target: HostInstance | null | undefined): void {
  if (!target) return;
  if (Platform.OS === 'web') {
    (target as unknown as { focus?: () => void }).focus?.();
    return;
  }
  AccessibilityInfo.sendAccessibilityEvent(target, 'focus');
}

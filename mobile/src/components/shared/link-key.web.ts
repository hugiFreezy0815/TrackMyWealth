/**
 * Web: Enter follows a `Pressable` with `accessibilityRole="link"` (WCAG 2.1.1).
 * react-native-web renders it as `<div role="link">` and skips onPress on Enter, expecting the
 * browser to click a real `<a href>`, which never happens for a div. Pressable forwards
 * `onKeyDown` on web, which React Native's types omit.
 */
export function linkKeyProps(onPress: () => void): object {
  return {
    onKeyDown: (
      event: Pick<KeyboardEvent, 'key' | 'repeat' | 'altKey' | 'ctrlKey' | 'metaKey' | 'shiftKey'> &
        Pick<KeyboardEvent, 'preventDefault'>,
    ) => {
      if (event.key !== 'Enter' || event.repeat) return;
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
      event.preventDefault();
      onPress();
    },
  };
}

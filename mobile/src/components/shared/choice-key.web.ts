/**
 * Web: Space selects a radio or checkbox chip (WCAG 2.1.1, APG radio and checkbox patterns).
 * react-native-web's Pressable activates only `role="button"` elements with Space; Enter already
 * works through onPress. Pressable forwards `onKeyDown` on web, which React Native's types omit.
 */
export function choiceKeyProps(onSelect: () => void, disabled: boolean): object {
  return {
    onKeyDown: (event: Pick<KeyboardEvent, 'key' | 'preventDefault'>) => {
      if (disabled || (event.key !== ' ' && event.key !== 'Spacebar')) return;
      // Without this the page scrolls as well.
      event.preventDefault();
      onSelect();
    },
  };
}

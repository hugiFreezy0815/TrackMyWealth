import { useEffect, useRef } from 'react';

/**
 * Web: Escape closes an open dialog (WCAG 2.1.2 / APG dialog pattern). react-native-web's Modal
 * does not call onRequestClose for Escape, so the dialog listens itself while it is open.
 */
export function useEscapeKey(
  active: boolean,
  onEscape: () => void,
  target:
    Pick<EventTarget, 'addEventListener' | 'removeEventListener'> | undefined = globalThis.document,
): void {
  const latest = useRef(onEscape);
  useEffect(() => {
    latest.current = onEscape;
  });
  useEffect(() => {
    if (!active || !target) return undefined;
    const listener = (event: Event) => {
      if ((event as KeyboardEvent).key === 'Escape') latest.current();
    };
    target.addEventListener('keydown', listener);
    return () => target.removeEventListener('keydown', listener);
  }, [active, target]);
}

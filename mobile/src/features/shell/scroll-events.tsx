import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useRef,
  type ReactNode,
  type RefObject,
} from 'react';
import { useWindowDimensions, type HostInstance } from 'react-native';

type Listener = () => void;
type ScrollEvents = { subscribe: (listener: Listener) => () => void };

const ShellScrollContext = createContext<ScrollEvents | null>(null);

/** Owned by ShellScreen: tells its content that the screen scrolled. */
export function useShellScrollEvents() {
  const listeners = useRef(new Set<Listener>());
  const events = useMemo<ScrollEvents>(
    () => ({
      subscribe: (listener) => {
        listeners.current.add(listener);
        return () => listeners.current.delete(listener);
      },
    }),
    [],
  );
  const notify = () => listeners.current.forEach((listener) => listener());
  return { events, notify };
}

export function ShellScrollProvider({
  events,
  children,
}: {
  events: ScrollEvents;
  children: ReactNode;
}) {
  return <ShellScrollContext.Provider value={events}>{children}</ShellScrollContext.Provider>;
}

type Measurable = Pick<HostInstance, 'measureInWindow'>;

/**
 * Calls `onVisible` while the view behind `ref` is inside the window: after its layout and on
 * every scroll of the surrounding ShellScreen. The caller guards against repeated calls.
 */
export function useWhenOnScreen(
  ref: RefObject<Measurable | null>,
  onVisible: () => void,
  enabled: boolean,
) {
  const scroll = useContext(ShellScrollContext);
  const { height } = useWindowDimensions();
  const latest = useRef(onVisible);
  useEffect(() => {
    latest.current = onVisible;
  });
  const check = useMemo(
    () => () => {
      if (!enabled) return;
      ref.current?.measureInWindow((_x, y) => {
        if (y <= height) latest.current();
      });
    },
    [enabled, height, ref],
  );
  useEffect(() => {
    if (!enabled || !scroll) return undefined;
    return scroll.subscribe(check);
  }, [check, enabled, scroll]);
  return check;
}

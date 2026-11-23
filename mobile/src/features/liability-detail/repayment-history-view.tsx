import { usePathname } from 'expo-router';
import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';

type RepaymentHistoryView = {
  showAll: boolean;
  expandedIds: readonly string[];
};

type SavedHistoryView = RepaymentHistoryView & { accountId: string };

const initialView: RepaymentHistoryView = { showAll: false, expandedIds: [] };

type HistoryStash = {
  /** Reads the view saved for this loan without using it up. */
  peek: (accountId: string) => RepaymentHistoryView | null;
  /** Drops the view saved for this loan once it has been restored. */
  clear: (accountId: string) => void;
  save: (view: SavedHistoryView) => void;
};

const HistoryContext = createContext<HistoryStash | null>(null);

/** The loan route itself, or the transaction route a repayment link pushed on top of it. */
function keepsSavedView(pathname: string, accountId: string) {
  return (
    pathname === `/accounts/${encodeURIComponent(accountId)}` ||
    /^\/accounts\/[^/]+\/transactions\/[^/]+$/.test(pathname)
  );
}

/**
 * At phone width a repayment link replaces the loan route, which unmounts the panel. The panel
 * saves its view when it opens a linked transaction, and restores it only on the way Back: any
 * other route, or a different signed-in user (`resetKey`), drops the saved view.
 */
export function RepaymentHistoryProvider({
  resetKey,
  children,
}: {
  resetKey?: string;
  children: ReactNode;
}) {
  const saved = useRef<SavedHistoryView | null>(null);
  const pathname = usePathname();
  useEffect(() => {
    if (saved.current && !keepsSavedView(pathname, saved.current.accountId)) saved.current = null;
  }, [pathname]);
  useEffect(() => {
    saved.current = null;
  }, [resetKey]);
  const value = useMemo<HistoryStash>(
    () => ({
      peek: (accountId) => (saved.current?.accountId === accountId ? saved.current : null),
      clear: (accountId) => {
        if (saved.current?.accountId === accountId) saved.current = null;
      },
      save: (view) => {
        saved.current = view;
      },
    }),
    [],
  );
  return <HistoryContext.Provider value={value}>{children}</HistoryContext.Provider>;
}

export function useRepaymentHistoryView(accountId: string) {
  const stash = useContext(HistoryContext);
  const [state, setState] = useState(() => ({
    accountId,
    view: stash?.peek(accountId) ?? initialView,
  }));
  if (state.accountId !== accountId) setState({ accountId, view: initialView });
  useEffect(() => {
    stash?.clear(accountId);
  }, [stash, accountId]);
  const view = state.accountId === accountId ? state.view : initialView;
  const setView = (next: RepaymentHistoryView) => setState({ accountId, view: next });
  /** Call before leaving for a linked transaction, so Back can restore this view. */
  const saveForBack = () => stash?.save({ ...view, accountId });
  return { view, setView, saveForBack };
}

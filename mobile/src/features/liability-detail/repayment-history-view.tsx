import { createContext, useContext, useState, type ReactNode } from 'react';

type RepaymentHistoryView = {
  showAll: boolean;
  expandedIds: readonly string[];
};

type SavedHistoryView = RepaymentHistoryView & { accountId: string };

const initialView: RepaymentHistoryView = { showAll: false, expandedIds: [] };
const HistoryContext = createContext<{
  saved: SavedHistoryView | null;
  save: (view: SavedHistoryView) => void;
} | null>(null);

/** The signed-in shell outlives its focused route. Remember the last loan view within that shell. */
export function RepaymentHistoryProvider({ children }: { children: ReactNode }) {
  const [saved, save] = useState<SavedHistoryView | null>(null);
  return <HistoryContext.Provider value={{ saved, save }}>{children}</HistoryContext.Provider>;
}

export function useRepaymentHistoryView(accountId: string) {
  const context = useContext(HistoryContext);
  const [localView, setLocalView] = useState(initialView);
  const view = context
    ? context.saved?.accountId === accountId
      ? context.saved
      : initialView
    : localView;
  const setView = (next: RepaymentHistoryView) => {
    if (context) context.save({ ...next, accountId });
    else setLocalView(next);
  };
  return { view, setView };
}

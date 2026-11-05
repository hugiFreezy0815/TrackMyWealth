/**
 * Throwaway, web-only #384 comparison, intentionally NOT a product screen.
 * Run: cd mobile && npm ci && npx expo start --web ; visit /spike-web-transactions.
 * Figma baseline: W06 https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=32-1072
 * Branch stays unmerged; only ADR 0007 should enter main after evidence and PO approval.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Platform, Pressable, ScrollView, Text, View } from 'react-native';

import {
  createTransactions,
  selectTransactions,
  toCsv,
  type DemoTransaction,
  type SortDirection,
  type SortField,
} from '../spikes/transaction-fixture';

type Mode = 'rnw' | 'dom';
const ROW_HEIGHT = 42;
const WINDOW_HEIGHT = 504;
const OVERSCAN = 6;
const columns: { field: SortField; label: string; width: string }[] = [
  { field: 'bookingDate', label: 'Date', width: '15%' },
  { field: 'description', label: 'Description', width: '31%' },
  { field: 'account', label: 'Account', width: '21%' },
  { field: 'category', label: 'Category', width: '17%' },
  { field: 'amount', label: 'Amount', width: '16%' },
];

const colors = {
  navy: '#0b1c3f',
  surface: '#ffffff',
  canvas: '#f4f7f8',
  border: '#e1e7ec',
  secondary: '#53627a',
  primary: '#00857a',
};

function money(row: DemoTransaction): string {
  return `${row.amount < 0 ? '−' : '+'} CHF ${Math.abs(row.amount).toFixed(2)}`;
}

function useVisibleRange(total: number, scrollY: number) {
  const start = Math.max(0, Math.floor(scrollY / ROW_HEIGHT) - OVERSCAN);
  const end = Math.min(total, Math.ceil((scrollY + WINDOW_HEIGHT) / ROW_HEIGHT) + OVERSCAN);
  return { start, end, above: start * ROW_HEIGHT, below: (total - end) * ROW_HEIGHT };
}

function rowText(row: DemoTransaction) {
  return [row.bookingDate, row.description, row.account, row.category, money(row)];
}

function NativeWebList({
  rows,
  selectedId,
  onSelect,
  onScroll,
  scrollY,
}: {
  rows: DemoTransaction[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  onScroll: (y: number) => void;
  scrollY: number;
}) {
  const range = useVisibleRange(rows.length, scrollY);
  return (
    <ScrollView
      accessibilityLabel="React Native Web virtualised transaction list"
      style={{ height: WINDOW_HEIGHT, backgroundColor: colors.surface }}
      onScroll={(event) => onScroll(event.nativeEvent.contentOffset.y)}
      scrollEventThrottle={16}
    >
      <View style={{ height: range.above }} />
      {rows.slice(range.start, range.end).map((row) => (
        <Pressable
          key={row.id}
          accessibilityRole="button"
          accessibilityLabel={rowText(row).join(', ')}
          accessibilityState={{ selected: row.id === selectedId }}
          onPress={() => onSelect(row.id)}
          style={{
            height: ROW_HEIGHT,
            flexDirection: 'row',
            alignItems: 'center',
            backgroundColor: selectedId === row.id ? '#e0f4ef' : colors.surface,
            borderBottomColor: colors.border,
            borderBottomWidth: 1,
            paddingHorizontal: 12,
          }}
        >
          <Text style={{ width: '15%', fontSize: 12, color: colors.secondary }}>
            {row.bookingDate}
          </Text>
          <Text style={{ width: '31%', fontSize: 13, color: colors.navy }} numberOfLines={1}>
            {row.description}
          </Text>
          <Text style={{ width: '21%', fontSize: 12, color: colors.secondary }} numberOfLines={1}>
            {row.account}
          </Text>
          <Text style={{ width: '17%', fontSize: 12, color: colors.secondary }}>
            {row.category}
          </Text>
          <Text
            style={{
              width: '16%',
              fontSize: 12,
              color: row.amount < 0 ? '#a32626' : colors.primary,
            }}
          >
            {money(row)}
          </Text>
        </Pressable>
      ))}
      <View style={{ height: range.below }} />
    </ScrollView>
  );
}

function DomTable({
  rows,
  selectedId,
  onSelect,
  onScroll,
  scrollY,
  onSort,
  sortField,
  sortDir,
}: {
  rows: DemoTransaction[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  onScroll: (y: number) => void;
  scrollY: number;
  onSort: (field: SortField) => void;
  sortField: SortField;
  sortDir: SortDirection;
}) {
  const range = useVisibleRange(rows.length, scrollY);
  return (
    <div
      aria-label="React DOM virtualised transaction table"
      style={{ height: WINDOW_HEIGHT, overflowY: 'auto', background: colors.surface }}
      onScroll={(event) => onScroll(event.currentTarget.scrollTop)}
    >
      <table
        style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed', fontSize: 13 }}
      >
        <thead style={{ position: 'sticky', top: 0, background: '#ecf1f4', zIndex: 1 }}>
          <tr>
            {columns.map((column) => (
              <th
                key={column.field}
                scope="col"
                style={{ width: column.width, textAlign: 'left', padding: 10 }}
              >
                <button
                  type="button"
                  onClick={() => onSort(column.field)}
                  aria-sort={undefined}
                  style={{
                    cursor: 'pointer',
                    background: 'transparent',
                    border: 0,
                    color: colors.navy,
                  }}
                >
                  {column.label} {sortField === column.field ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                </button>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {range.above > 0 && (
            <tr aria-hidden="true">
              <td colSpan={5} style={{ height: range.above, padding: 0 }} />
            </tr>
          )}
          {rows.slice(range.start, range.end).map((row, index) => (
            <tr
              key={row.id}
              aria-selected={row.id === selectedId}
              style={{
                height: ROW_HEIGHT,
                background: row.id === selectedId ? '#e0f4ef' : index % 2 ? '#fbfcfd' : 'white',
              }}
            >
              {rowText(row).map((value, cell) => (
                <td
                  key={cell}
                  style={{
                    borderBottom: `1px solid ${colors.border}`,
                    padding: '8px 10px',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {cell === 1 ? (
                    <button
                      type="button"
                      onClick={() => onSelect(row.id)}
                      aria-label={`Select ${row.description}`}
                      style={{
                        background: 'transparent',
                        border: 0,
                        cursor: 'pointer',
                        textAlign: 'left',
                        color: colors.navy,
                      }}
                    >
                      {value}
                    </button>
                  ) : (
                    value
                  )}
                </td>
              ))}
            </tr>
          ))}
          {range.below > 0 && (
            <tr aria-hidden="true">
              <td colSpan={5} style={{ height: range.below, padding: 0 }} />
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

export default function WebTransactionSpike() {
  const [mode, setMode] = useState<Mode>(() =>
    typeof window !== 'undefined' &&
    new URLSearchParams(window.location.search).get('renderer') === 'dom'
      ? 'dom'
      : 'rnw',
  );
  const [count, setCount] = useState<1_000 | 10_000>(() =>
    typeof window !== 'undefined' &&
    new URLSearchParams(window.location.search).get('rows') === '10000'
      ? 10_000
      : 1_000,
  );
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState('');
  const [sortField, setSortField] = useState<SortField>('bookingDate');
  const [sortDir, setSortDir] = useState<SortDirection>('desc');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [scrollY, setScrollY] = useState(0);
  const [renderMs, setRenderMs] = useState<number | null>(null);
  const [scrollStats, setScrollStats] = useState<{ frames: number; over50ms: number }>({
    frames: 0,
    over50ms: 0,
  });
  const lastScroll = useRef<number | null>(null);
  const started = useRef<number>(0);
  const all = useMemo(() => createTransactions(count), [count]);
  const rows = useMemo(
    () => selectTransactions(all, search, category, sortField, sortDir),
    [all, search, category, sortField, sortDir],
  );
  const selected = rows.find((r) => r.id === selectedId) ?? null;
  const range = useVisibleRange(rows.length, scrollY);

  useEffect(() => {
    started.current = performance.now();
    const raf = requestAnimationFrame(() => {
      setRenderMs(Number((performance.now() - started.current).toFixed(2)));
    });
    return () => cancelAnimationFrame(raf);
  }, [mode, count, search, category, sortField, sortDir]);

  const changeScroll = useCallback((y: number) => {
    const now = performance.now();
    if (lastScroll.current != null) {
      const elapsed = now - lastScroll.current;
      setScrollStats((prev) => ({
        frames: prev.frames + 1,
        over50ms: prev.over50ms + Number(elapsed > 50),
      }));
    }
    lastScroll.current = now;
    setScrollY(y);
  }, []);

  const chooseMode = useCallback((next: Mode) => {
    setMode(next);
    setSelectedId(null);
    setScrollY(0);
    setScrollStats({ frames: 0, over50ms: 0 });
    lastScroll.current = null;
  }, []);

  const sort = useCallback(
    (field: SortField) => {
      setSortDir((old) => (sortField === field && old === 'asc' ? 'desc' : 'asc'));
      setSortField(field);
      setScrollY(0);
    },
    [sortField],
  );

  const moveSelection = useCallback(
    (delta: number) => {
      if (!rows.length) return;
      const i = rows.findIndex((r) => r.id === selectedId);
      const next = Math.min(rows.length - 1, Math.max(0, i + delta));
      setSelectedId(rows[next].id);
      setScrollY(next * ROW_HEIGHT);
    },
    [rows, selectedId],
  );

  const saveCsv = () => {
    const blob = new Blob([toCsv(rows)], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'synthetic-transactions.csv';
    link.click();
    URL.revokeObjectURL(url);
  };

  if (Platform.OS !== 'web') {
    return (
      <View style={{ padding: 24 }}>
        <Text>Web-only architecture experiment. Open in a browser.</Text>
      </View>
    );
  }

  return (
    <div
      style={{
        fontFamily: 'system-ui, sans-serif',
        minHeight: '100vh',
        background: colors.canvas,
        padding: 24,
        color: colors.navy,
      }}
    >
      <main data-benchmark-mode={mode} style={{ maxWidth: 1280, margin: 'auto' }}>
        <h1 style={{ marginBottom: 4 }}>Transaction list · architecture spike #384</h1>
        <p style={{ color: colors.secondary }}>
          Disposable synthetic benchmark based on Figma W06 (248px sidebar, table and detail panel).
          Not a production screen. Compare the two implementations with the same rows.
        </p>
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: 14,
            alignItems: 'center',
            margin: '16px 0',
          }}
        >
          <fieldset style={{ borderColor: colors.border }}>
            <legend>Renderer</legend>
            <label>
              <input
                type="radio"
                name="renderer"
                checked={mode === 'rnw'}
                onChange={() => chooseMode('rnw')}
              />{' '}
              React Native Web
            </label>{' '}
            <label>
              <input
                type="radio"
                name="renderer"
                checked={mode === 'dom'}
                onChange={() => chooseMode('dom')}
              />{' '}
              React DOM table
            </label>
          </fieldset>
          <label>
            Rows{' '}
            <select
              value={count}
              onChange={(e) => {
                setCount(Number(e.target.value) as 1_000 | 10_000);
                setScrollY(0);
              }}
            >
              <option value={1_000}>1,000</option>
              <option value={10_000}>10,000</option>
            </select>
          </label>
          <label>
            Search{' '}
            <input
              aria-label="Search description or account"
              value={search}
              onChange={(e) => {
                setSearch(e.target.value);
                setScrollY(0);
              }}
            />
          </label>
          <label>
            Category{' '}
            <select
              value={category}
              onChange={(e) => {
                setCategory(e.target.value);
                setScrollY(0);
              }}
            >
              <option value="">All</option>
              {['Groceries', 'Transport', 'Housing', 'Salary', 'Other'].map((x) => (
                <option key={x}>{x}</option>
              ))}
            </select>
          </label>
          <label>
            Sort{' '}
            <select value={sortField} onChange={(e) => sort(e.target.value as SortField)}>
              {columns.map((x) => (
                <option key={x.field} value={x.field}>
                  {x.label}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            onClick={() => setSortDir((v) => (v === 'asc' ? 'desc' : 'asc'))}
            aria-label="Reverse sort"
          >
            {sortDir === 'asc' ? 'Ascending ↑' : 'Descending ↓'}
          </button>
          <button type="button" onClick={saveCsv}>
            Download filtered CSV
          </button>
        </div>
        <p data-benchmark-count={rows.length} aria-live="polite" style={{ fontSize: 13 }}>
          {rows.length.toLocaleString()} filtered transactions · {range.end - range.start}{' '}
          virtualised rows mounted · post-change paint callback: {renderMs ?? '–'} ms · scroll
          events: {scrollStats.frames}· gaps &gt;50ms: {scrollStats.over50ms}.
        </p>
        <div
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
              e.preventDefault();
              moveSelection(e.key === 'ArrowDown' ? 1 : -1);
            }
            if (e.key === 'Enter' && selectedId) {
              document.getElementById('spike-details')?.focus();
            }
          }}
          aria-label="Transaction list. Up and down arrows move selection; Enter moves to details."
          style={{
            display: 'grid',
            gridTemplateColumns: 'minmax(0, 1fr) 300px',
            gap: 16,
            border: `1px solid ${colors.border}`,
            background: colors.surface,
          }}
        >
          <section data-benchmark-list={mode} style={{ minWidth: 0 }} aria-label="Transaction table">
            {mode === 'rnw' ? (
              <>
                <div style={{ padding: 12, borderBottom: `1px solid ${colors.border}` }}>
                  React Native Web View/Pressable/ScrollView rows, virtualised
                </div>
                <NativeWebList
                  rows={rows}
                  scrollY={scrollY}
                  selectedId={selectedId}
                  onScroll={changeScroll}
                  onSelect={setSelectedId}
                />
              </>
            ) : (
              <DomTable
                rows={rows}
                scrollY={scrollY}
                selectedId={selectedId}
                onScroll={changeScroll}
                onSelect={setSelectedId}
                onSort={sort}
                sortField={sortField}
                sortDir={sortDir}
              />
            )}
          </section>
          <aside
            id="spike-details"
            tabIndex={-1}
            aria-label="Selected transaction details"
            style={{ padding: 16, borderLeft: `1px solid ${colors.border}` }}
          >
            <h2 style={{ fontSize: 18, marginTop: 0 }}>Details</h2>
            {selected ? (
              <dl>
                {rowText(selected).map((value, i) => (
                  <React.Fragment key={columns[i].field}>
                    <dt style={{ color: colors.secondary }}>{columns[i].label}</dt>
                    <dd style={{ margin: '2px 0 12px' }}>{value}</dd>
                  </React.Fragment>
                ))}
              </dl>
            ) : (
              <p>Choose a transaction row to inspect it.</p>
            )}
          </aside>
        </div>
        <p style={{ color: colors.secondary, fontSize: 12 }}>
          Benchmark policy: measure both renderers in the same browser/device (cold and warm), at
          1,000 and 10,000 rows. “Paint callback” is not a Core Web Vital; measure performance
          traces, scrolling and screen reader behaviour independently. The data is wholly invented.
        </p>
      </main>
    </div>
  );
}

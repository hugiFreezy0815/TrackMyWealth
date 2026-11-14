import { fireEvent, render, screen } from '@testing-library/react-native';
import { IntlProvider } from 'react-intl';
import * as SecureStore from 'expo-secure-store';

import de from '@/i18n/de.json';
import en from '@/i18n/en.json';
import { HistoryPanel, historyForDisplay } from './history-panel';
import { HISTORY_PERIOD_KEY, historyPeriodWrites, resetHistoryPeriodForTests } from './period';
import type { ValueHistory } from './value-chart';

jest.mock('@/global.css', () => ({}));
let mockHidden = false;
jest.mock('@/features/privacy/amount-visibility', () => ({
  useAmountsHidden: () => mockHidden,
}));
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn().mockResolvedValue(null),
  setItemAsync: jest.fn().mockResolvedValue(undefined),
}));

const idle: {
  isPending: boolean;
  isPlaceholderData: boolean;
  isError: boolean;
  error: unknown;
  refetch: jest.Mock;
} = {
  isPending: false,
  isPlaceholderData: false,
  isError: false,
  error: null,
  refetch: jest.fn(),
};

function history(overrides: Partial<ValueHistory> = {}): ValueHistory {
  return {
    currency: 'CHF',
    period: '1M',
    from: '2026-10-13',
    to: '2026-11-13',
    points: [
      { date: '2026-10-13', value: '1000', estimated: false },
      { date: '2026-11-13', value: '1100', estimated: false },
    ],
    change: {
      amount: '100.00',
      percent: '10.00',
      contributions: '60.00',
      marketAndFx: '40.00',
      capitalMovements: null,
    },
    sections: [],
    complete: true,
    warnings: [],
    warningCodes: [],
    ...overrides,
  };
}

async function renderPanel({
  data = history(),
  query = idle,
  online = true,
  period = '1M' as const,
  hidden = false,
  locale = 'en' as 'en' | 'de',
}: {
  /** `null` renders the panel without a history. */
  data?: ValueHistory | null;
  query?: typeof idle;
  online?: boolean;
  period?: ValueHistory['period'];
  hidden?: boolean;
  locale?: 'en' | 'de';
} = {}) {
  await render(
    <IntlProvider locale={locale} messages={locale === 'de' ? de : en}>
      <HistoryPanel
        query={query}
        data={data ?? undefined}
        online={online}
        period={period}
        hidden={hidden}
      />
    </IntlProvider>,
  );
}

type Node = { children?: unknown[] | null } | string | null | Node[];

/** Visible strings in document order. */
function texts(): string[] {
  const out: string[] = [];
  const walk = (node: unknown) => {
    if (node == null) return;
    if (Array.isArray(node)) return node.forEach(walk);
    if (typeof node === 'string') return void out.push(node);
    ((node as Exclude<Node, string | null | Node[]>).children ?? []).forEach(walk);
  };
  walk(screen.toJSON());
  return out;
}

beforeEach(() => {
  mockHidden = false;
  resetHistoryPeriodForTests();
  idle.refetch.mockReset();
});

describe('HistoryPanel', () => {
  it('follows the M03 order: change chip with its period, split, chart, period switch', async () => {
    await renderPanel();
    const order = texts();
    const at = (text: string | RegExp) =>
      order.findIndex((value) => (typeof text === 'string' ? value === text : text.test(value)));
    expect(at('+10.0%')).toBeGreaterThan(-1);
    expect(at('past month')).toBeGreaterThan(at('+10.0%'));
    expect(at('Net saving')).toBeGreaterThan(at('past month'));
    expect(at('Market & FX')).toBeGreaterThan(at('Net saving'));
    expect(at('·')).toBeGreaterThan(at('Net saving'));
    expect(at('Capital movements')).toBe(-1);
    // The chart's date labels, then the period chips as the card's last row.
    expect(at('13 Oct 2026')).toBeGreaterThan(at('Market & FX'));
    expect(at(/^1W/)).toBeGreaterThan(at('13 Nov 2026'));
    expect(at(/^Max/)).toBe(order.length - 1);
    expect(screen.getByRole('radio', { name: '1M, Past month' })).toBeChecked();
  });

  it('shows a possibly distorted change neutral, without a percentage, and says why', async () => {
    await renderPanel({
      data: history({ complete: false, warningCodes: ['VALUE_UNKNOWN'] }),
    });
    expect(screen.queryByText('+10.0%')).toBeNull();
    expect(screen.getByText(/This change may be off/)).toBeTruthy();
    expect(
      screen.getByText('A historical value is unavailable for at least one account.'),
    ).toBeTruthy();
    expect(screen.getByLabelText('plus 100.00 Swiss francs')).toBeTruthy();
  });

  it('keeps the percentage when only the split is incomplete', async () => {
    await renderPanel({
      data: history({
        complete: false,
        change: { ...history().change, contributions: null, marketAndFx: null },
        warningCodes: ['ATTRIBUTION_PENDING_REVIEW'],
      }),
    });
    expect(screen.getByText('+10.0%')).toBeTruthy();
    expect(screen.queryByText(/This change may be off/)).toBeNull();
    expect(screen.queryByText('Net saving')).toBeNull();
    expect(screen.getByText(/Some transactions still need review/)).toBeTruthy();
    expect(screen.getByText(/parts of the change are incomplete/)).toBeTruthy();
  });

  it('masks the chip, the split and the chart labels when amounts are hidden', async () => {
    mockHidden = true;
    await renderPanel({ hidden: true });
    const visible = texts().join(' ');
    expect(visible).not.toMatch(/100\.00|60\.00|40\.00|10\.0%|Oct 2026/);
    expect(visible).toMatch(/Net saving/);
    expect(
      screen.getByRole('image', { name: 'Value history. Amounts and dates are hidden.' }),
    ).toBeTruthy();
  });

  it('offers a retry when the history fails and shows a skeleton while it loads', async () => {
    const failing = { ...idle, isError: true, error: new Error('boom') };
    await renderPanel({ data: null, query: failing });
    await fireEvent.press(screen.getByText('Try again'));
    expect(failing.refetch).toHaveBeenCalledTimes(1);

    await renderPanel({ data: null, query: { ...idle, isPending: true } });
    expect(screen.getByLabelText('Loading')).toBeTruthy();
    // The period switch stays usable while the history loads.
    expect(screen.getByRole('radio', { name: '1W, Past week' })).toBeTruthy();
  });

  it('explains an uncached period offline', async () => {
    await renderPanel({ data: null, online: false, period: '1Y' });
    expect(screen.getByText('The 1Y period is unavailable offline.')).toBeTruthy();
  });

  it('switches the shared period from a chip and saves it on the device', async () => {
    await renderPanel();
    await fireEvent.press(screen.getByRole('radio', { name: 'YTD, Year to date' }));
    await historyPeriodWrites();
    expect(SecureStore.setItemAsync).toHaveBeenLastCalledWith(HISTORY_PERIOD_KEY, 'YTD');
  });

  it('renders in German', async () => {
    await renderPanel({
      locale: 'de',
      data: history({ complete: false, warningCodes: ['ATTRIBUTION_FX_UNAVAILABLE'] }),
    });
    expect(screen.getByText('letzter Monat')).toBeTruthy();
    expect(screen.getByText('Nettoersparnis')).toBeTruthy();
    expect(screen.getByText('Einige historische Wechselkurse sind nicht verfügbar.')).toBeTruthy();
    expect(screen.getByRole('radio', { name: '1J, Letztes Jahr' })).toBeTruthy();
  });
});

describe('historyForDisplay', () => {
  const monthly = history();

  it('shows the requested period, and offline only falls back to a placeholder series', () => {
    expect(historyForDisplay({ data: monthly, isPlaceholderData: false }, '1M', true)).toBe(
      monthly,
    );
    expect(historyForDisplay({ data: monthly, isPlaceholderData: true }, '1Y', true)).toBe(
      undefined,
    );
    expect(historyForDisplay({ data: monthly, isPlaceholderData: true }, '1Y', false)).toBe(
      monthly,
    );
    expect(historyForDisplay({ data: undefined, isPlaceholderData: false }, '1M', false)).toBe(
      undefined,
    );
  });
});

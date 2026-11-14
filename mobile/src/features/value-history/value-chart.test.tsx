import { render, screen } from '@testing-library/react-native';
import { IntlProvider } from 'react-intl';

import en from '@/i18n/en.json';
import { chartPoints, ValueChart, type ValueHistory } from './value-chart';

jest.mock('@/global.css', () => ({}));
jest.mock('react-native-svg', () => ({
  __esModule: true,
  default: 'Svg',
  Line: 'Line',
  Circle: 'Circle',
}));

const point = (date: string, value: string, estimated = false) => ({ date, value, estimated });

function history(points: ValueHistory['points']): ValueHistory {
  return {
    currency: 'CHF',
    period: '1M',
    from: '2026-10-01',
    to: '2026-10-31',
    points,
    change: {
      amount: '10.00',
      percent: '1.0',
      contributions: null,
      marketAndFx: null,
      capitalMovements: null,
    },
    sections: [],
    complete: true,
    warnings: [],
    warningCodes: [],
  };
}

type HostNode = { type: string; props: Record<string, unknown>; children?: unknown[] | null };

function hostNodes(type: string): HostNode[] {
  const found: HostNode[] = [];
  const walk = (node: unknown) => {
    if (node == null || typeof node === 'string') return;
    if (Array.isArray(node)) return node.forEach(walk);
    const host = node as HostNode;
    if (host.type === type) found.push(host);
    (host.children ?? []).forEach(walk);
  };
  walk(screen.toJSON());
  return found;
}

async function renderChart(data: ValueHistory, hidden = false) {
  await render(
    <IntlProvider locale="en" messages={en}>
      <ValueChart data={data} hidden={hidden} />
    </IntlProvider>,
  );
}

describe('chartPoints', () => {
  it('scales time to x and value to y inside the padding', () => {
    const coords = chartPoints([
      point('2026-10-01', '100'),
      point('2026-10-11', '300'),
      point('2026-10-31', '200'),
    ]);
    expect(coords.map(({ x }) => Math.round(x))).toEqual([5, 122, 355]);
    expect(coords.map(({ y }) => Math.round(y))).toEqual([115, 5, 60]);
  });

  it('centres a single point and draws a flat series in the middle', () => {
    expect(chartPoints([point('2026-10-01', '5')])).toEqual([
      expect.objectContaining({ x: 180, y: 60 }),
    ]);
    expect(
      chartPoints([point('2026-10-01', '5'), point('2026-10-02', '5')]).map(({ y }) => y),
    ).toEqual([60, 60]);
  });

  it('skips values that are not plain decimals and dates that do not parse', () => {
    const coords = chartPoints([
      point('2026-10-01', '1e3'),
      point('not a date', '10'),
      point('2026-10-02', ''),
      point('2026-10-03', '-12.50'),
    ]);
    expect(coords.map(({ point: kept }) => kept.value)).toEqual(['-12.50']);
    expect(chartPoints([])).toEqual([]);
  });
});

describe('ValueChart', () => {
  it('draws estimated stretches dashed and describes the chart with dates, values and change', async () => {
    await renderChart(
      history([
        point('2026-10-01', '1000', true),
        point('2026-10-15', '1100'),
        point('2026-10-31', '1200'),
      ]),
    );
    const lines = hostNodes('Line');
    expect(lines.map((line) => line.props.strokeDasharray)).toEqual(['4 3', undefined]);
    expect(
      screen.getByRole('image', {
        name: 'Value history from 1 Oct 2026 to 31 Oct 2026. From 1000.00 Swiss francs to 1200.00 Swiss francs. Change plus 10.00 Swiss francs. Includes estimated values.',
      }),
    ).toBeTruthy();
    expect(screen.getByText('Dashed lines show estimated values.')).toBeTruthy();
  });

  it('keeps line keys unique when the server repeats a date', async () => {
    const error = jest.spyOn(console, 'error').mockImplementation(() => {});
    try {
      await renderChart(
        history([
          point('2026-10-01', '1'),
          point('2026-10-02', '2'),
          point('2026-10-02', '3'),
          point('2026-10-03', '4'),
        ]),
      );
      expect(hostNodes('Line')).toHaveLength(3);
      expect(error.mock.calls.flat().join(' ')).not.toMatch(/same key/);
    } finally {
      error.mockRestore();
    }
  });

  it('draws one dot for a single point and hides labels and values when amounts are hidden', async () => {
    await renderChart(history([point('2026-10-31', '1200')]), true);
    expect(hostNodes('Circle')).toHaveLength(1);
    expect(hostNodes('Line')).toHaveLength(0);
    expect(
      screen.getByRole('image', { name: 'Value history. Amounts and dates are hidden.' }),
    ).toBeTruthy();
    expect(screen.queryByText(/31 Oct 2026|1.?200/, { includeHiddenElements: true })).toBeNull();
  });

  it('says so when the period has no values', async () => {
    await renderChart(history([]));
    expect(screen.getByText('No values are available for this period.')).toBeTruthy();
  });
});

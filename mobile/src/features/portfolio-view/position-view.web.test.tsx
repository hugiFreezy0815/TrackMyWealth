import { fireEvent, screen } from '@testing-library/react-native';

import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { renderWithIntl } from '@/i18n/test-utils';
import type { Position } from './model';
import { PositionView } from './position-view.web';

jest.mock('@/global.css', () => ({}));
jest.mock('expo-symbols', () => ({ SymbolView: 'SymbolView' }));
jest.mock('@/features/privacy/amount-visibility', () => ({
  useAmountsHidden: jest.fn(() => false),
}));
const mockWindow = { width: 1280, height: 800, scale: 1, fontScale: 1 };
jest.mock('react-native/Libraries/Utilities/useWindowDimensions', () => ({
  __esModule: true,
  default: () => mockWindow,
}));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => ({
    textPrimary: '#0B1C3F',
    textSecondary: '#53627A',
    brandLink: '#006D64',
    borderDefault: '#E1E7EC',
    statusGain: '#007D55',
    statusLoss: '#C42F37',
    statusWarning: '#8F5200',
    statusWarningSubtle: '#FFF4E5',
    statusInfo: '#006EA6',
    statusInfoSubtle: '#E7F4F9',
    bgSubtle: '#ECF1F4',
  }),
}));

const positions: Position[] = [
  {
    securityId: 's-1',
    displayName: 'iShares Core MSCI World',
    isin: 'IE00B4L5Y983',
    quantity: '310.000000',
    price: '98.43',
    priceCurrency: 'EUR',
    value: '30512.55',
    valueInAccountCurrency: '30512.55',
    costBasis: '27439.00',
    costBasisEstimated: false,
    unrealisedGain: '3073.55',
    unrealisedGainPercent: '11.20',
    valueStatus: 'CURRENT',
  },
  {
    securityId: 's-2',
    displayName: 'Apple',
    isin: null,
    quantity: '5',
    price: '230.00',
    priceCurrency: 'USD',
    value: '1150.00',
    valueInAccountCurrency: '1061.20',
    costBasis: '900.00',
    costBasisEstimated: false,
    unrealisedGain: null,
    unrealisedGainPercent: null,
    valueStatus: 'ESTIMATED',
  },
];

type JsonNode = {
  type: string;
  props: Record<string, unknown>;
  children: (JsonNode | string)[] | null;
};

/** Host elements of one type (RNTL 14 has no UNSAFE_root: walk the rendered JSON). */
function host(type: string): JsonNode[] {
  const found: JsonNode[] = [];
  const walk = (node: unknown): void => {
    if (node == null || typeof node === 'string') return;
    if (Array.isArray(node)) return node.forEach(walk);
    const element = node as JsonNode;
    if (element.type === type) found.push(element);
    element.children?.forEach(walk);
  };
  walk(screen.toJSON());
  return found;
}
const textOf = (node: JsonNode): string =>
  (node.children ?? [])
    .map((child) => (typeof child === 'string' ? child : textOf(child)))
    .join('');

async function renderTable() {
  const onOpen = jest.fn();
  await renderWithIntl(
    <PositionView positions={positions} currency="EUR" accountName="DKB Depot" onOpen={onOpen} />,
  );
  return onOpen;
}

beforeEach(() => {
  mockWindow.width = 1280;
  jest.mocked(useAmountsHidden).mockReturnValue(false);
});

describe('Depot positions on web (ADR 0007, W02/W03)', () => {
  it('is a semantic table with a caption and four column headers', async () => {
    await renderTable();
    expect(host('table')).toHaveLength(1);
    expect(textOf(host('caption')[0])).toBe('Positions in DKB Depot');
    const headers = host('th');
    expect(headers.map((th) => th.props.scope)).toEqual(['col', 'col', 'col', 'col']);
    expect(headers.map(textOf)).toEqual(['Position', 'Units', 'Value', 'Unrealised gain']);
    expect(host('tr')).toHaveLength(3);
  });

  it('shows name + ISIN, units, value and the gain amount with its percentage', async () => {
    await renderTable();
    expect(screen.getByText('IE00B4L5Y983')).toBeTruthy();
    expect(screen.getByText('310')).toBeTruthy();
    expect(screen.getByText("EUR 30'512.55")).toBeTruthy();
    expect(screen.getByText("+EUR 3'073.55")).toBeTruthy();
    expect(screen.getByText('+11.2%')).toBeTruthy();
    // A holding priced in another currency has no gain (FR-PERF-018).
    expect(screen.getByText('Gain not available in USD')).toBeTruthy();
  });

  it('opens the depot from the focusable position link', async () => {
    const onOpen = await renderTable();
    await fireEvent.press(screen.getByRole('link', { name: /^iShares Core MSCI World,/ }));
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('masks units, values and gains while "Hide amounts" is on', async () => {
    jest.mocked(useAmountsHidden).mockReturnValue(true);
    await renderTable();
    expect(screen.queryByText('310')).toBeNull();
    expect(screen.queryByText('+11.2%')).toBeNull();
    expect(screen.getAllByText('••••').length).toBeGreaterThanOrEqual(6);
  });

  it('falls back to the list below 600 px, where four columns do not fit', async () => {
    mockWindow.width = 390;
    await renderTable();
    expect(host('table')).toHaveLength(0);
    expect(screen.getByText('310 units · IE00B4L5Y983')).toBeTruthy();
  });
});

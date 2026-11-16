import { fireEvent, render, screen } from '@testing-library/react-native';
import { useIntl } from 'react-intl';
import { Platform, StyleSheet } from 'react-native';

import type { components } from '@/api/schema';
import { withIntl } from '@/i18n/test-utils';
import { columnsFor, figureSize, SectionTiles } from './section-tiles';

jest.mock('@/global.css', () => ({}));
// The screen passes `hidden` from the same app-wide setting; here it is driven by the prop.
jest.mock('@/features/privacy/amount-visibility', () => ({ useAmountsHidden: () => false }));

type NetWorth = components['schemas']['NetWorthResponse'];

const netWorth = (sections: NetWorth['sections']): NetWorth => ({
  currency: 'CHF',
  asOf: '2026-11-09',
  totalAssets: '0',
  totalLiabilities: '0',
  netWorth: '0',
  complete: true,
  approximate: false,
  warnings: [],
  accounts: [],
  sections,
});
const section = (
  name: NetWorth['sections'][number]['section'],
  subtotal: string,
  valueStatus: NetWorth['sections'][number]['valueStatus'] = 'CURRENT',
) => ({ section: name, subtotal, valueStatus, complete: true, accountCount: 1 });

// SectionTiles receives intl from the screen; this wrapper reads it inside the provider.
function Tiles({ data, hidden = false }: { data: NetWorth; hidden?: boolean }) {
  return <SectionTiles data={data} hidden={hidden} intl={useIntl()} />;
}

async function layout(width: number) {
  await fireEvent(screen.getByTestId('overview-section-tiles'), 'layout', {
    nativeEvent: { layout: { width, height: 300, x: 0, y: 0 } },
  });
}

describe('section tiles (M03/W01)', () => {
  const originalOS = Platform.OS;

  afterEach(() => {
    Platform.OS = originalOS;
  });

  it('uses four columns on web only when each tile gets at least 160 px', () => {
    Platform.OS = 'web';
    expect(columnsFor(675)).toBe(2);
    expect(columnsFor(676)).toBe(4);
  });

  it.each(['ios', 'android'] as const)('keeps two columns on wide %s layouts', async (os) => {
    Platform.OS = os;
    await render(withIntl(<Tiles data={netWorth([section('DEPOT', '94582.15')])} />));
    await layout(724);
    expect(StyleSheet.flatten(screen.getByTestId('overview-section-DEPOT').props.style).width).toBe(
      356,
    );
  });

  it('keeps the 16 px figure when it fits and steps down instead of wrapping', () => {
    expect(figureSize("CHF 50'901.17", 141)).toBe(16);
    expect(figureSize("−CHF 650'000.00", 141)).toBe(15);
    expect(figureSize("−CHF 1'250'000.00", 141)).toBe(13);
    expect(figureSize("CHF 123'456'789'012.00", 60)).toBe(11);
    expect(figureSize('anything', 0)).toBe(16);
  });

  it('lays two tiles per row on a 390 px phone with each figure on one line', async () => {
    await render(
      withIntl(
        <Tiles
          data={netWorth([
            section('CASH_AND_SAVINGS', '50901.17'),
            section('PROPERTY_AND_MORTGAGE', '-650000.00', 'ESTIMATED'),
          ])}
        />,
      ),
    );
    await layout(358);
    const tile = StyleSheet.flatten(
      screen.getByTestId('overview-section-CASH_AND_SAVINGS').props.style,
    );
    expect(tile.width).toBe(173);
    const small = screen.getByTestId('overview-section-CASH_AND_SAVINGS-amount');
    expect(small.props.numberOfLines).toBe(1);
    expect(StyleSheet.flatten(small.props.style).fontSize).toBe(16);
    const large = screen.getByTestId('overview-section-PROPERTY_AND_MORTGAGE-amount');
    expect(large.props.children).toBe("−CHF 650'000.00");
    expect(StyleSheet.flatten(large.props.style).fontSize).toBe(15);
    expect(
      screen.getByLabelText('Property & mortgage, minus 650000.00 Swiss francs, Estimated'),
    ).toBeTruthy();
  });

  it('uses four tiles per row in a wide web column', async () => {
    Platform.OS = 'web';
    await render(withIntl(<Tiles data={netWorth([section('DEPOT', '94582.15')])} />));
    await layout(724);
    expect(StyleSheet.flatten(screen.getByTestId('overview-section-DEPOT').props.style).width).toBe(
      172,
    );
  });

  it('never reads the digits of a hidden subtotal', async () => {
    await render(withIntl(<Tiles data={netWorth([section('DEPOT', '94582.15')])} hidden />));
    expect(screen.getByLabelText('Depot, hidden')).toBeTruthy();
    expect(screen.getByTestId('overview-section-DEPOT-amount').props.children).toBe('••••');
  });

  it('renders only sections returned by the server and keeps their order', async () => {
    await render(
      withIntl(
        <Tiles
          data={netWorth([
            section('DEPOT', '94582.15'),
            { ...section('CASH_AND_SAVINGS', '50901.17'), complete: false },
          ])}
        />,
      ),
    );

    expect(screen.getAllByTestId(/^overview-section-[A-Z_]+$/)).toEqual([
      screen.getByTestId('overview-section-DEPOT'),
      screen.getByTestId('overview-section-CASH_AND_SAVINGS'),
    ]);
    expect(screen.queryByText('Credit card')).toBeNull();
    expect(screen.getByText('Not all values are included')).toBeTruthy();
  });

  it('omits the tile grid when the server returns no sections', async () => {
    await render(withIntl(<Tiles data={netWorth([])} />));
    expect(screen.queryByTestId('overview-section-tiles')).toBeNull();
  });
});

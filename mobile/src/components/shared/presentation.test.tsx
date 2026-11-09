import { fireEvent, screen } from '@testing-library/react-native';

import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { renderWithIntl } from '@/i18n/test-utils';

import {
  AccountRow,
  ChangeChip,
  ChipGroup,
  InstitutionLogo,
  KeyValueList,
  PortfolioTabs,
  Stepper,
  TransactionRow,
} from './presentation';

jest.mock('@/global.css', () => ({}));
jest.mock('@/features/privacy/amount-visibility', () => ({
  useAmountsHidden: jest.fn(() => false),
}));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => ({
    textPrimary: '#0B1C3F',
    textSecondary: '#53627A',
    textOnBrand: '#FFFFFF',
    bgSurface: '#FFFFFF',
    bgSubtle: '#ECF1F4',
    bgInverse: '#0B1C3F',
    bgOverlay: '#0B1C3F99',
    borderDefault: '#E1E7EC',
    borderInteractive: '#64758A',
    brandSubtle: '#E0F5F1',
    brandLink: '#006D64',
    statusGain: '#0F7A3A',
    statusGainSubtle: '#E3F7E8',
    statusLoss: '#C42F37',
    statusLossSubtle: '#FDECEC',
    statusInfo: '#1F5CC4',
    statusInfoSubtle: '#E6EFFF',
    statusWarning: '#8F5200',
    statusWarningSubtle: '#FFF3D9',
  }),
}));
jest.mock('expo-image', () => ({ Image: 'Image' }));
jest.mock('expo-symbols', () => ({ SymbolView: 'SymbolView' }));

type Node = { type: string; props: Record<string, unknown>; children: (Node | string)[] | null };
function findHost(node: unknown, type: string): Node | undefined {
  if (!node || typeof node !== 'object') return undefined;
  if (Array.isArray(node)) {
    for (const child of node) {
      const found = findHost(child, type);
      if (found) return found;
    }
    return undefined;
  }
  const host = node as Node;
  return host.type === type ? host : findHost(host.children, type);
}

describe('shared navigation and financial rows', () => {
  it('tracks selected portfolio tabs and exposes accessible states', async () => {
    const onSelect = jest.fn();
    await renderWithIntl(
      <PortfolioTabs
        tabs={[
          { id: 'a', label: 'All' },
          { id: 'b', label: 'Bank' },
        ]}
        selected="a"
        onSelect={onSelect}
      />,
    );
    expect(screen.getByRole('tab', { name: 'All' }).props.accessibilityState.selected).toBe(true);
    await fireEvent.press(screen.getByRole('tab', { name: 'Bank' }));
    expect(onSelect).toHaveBeenCalledWith('b');
  });

  it('exposes single-choice chips as a checked radio group', async () => {
    const onSelect = jest.fn();
    await renderWithIntl(
      <ChipGroup
        label="Type"
        options={[
          { value: 'cash', label: 'Cash' },
          { value: 'depot', label: 'Depot' },
        ]}
        selected={['depot']}
        onSelect={onSelect}
      />,
    );
    expect(screen.getByLabelText('Type').props.accessibilityRole).toBe('radiogroup');
    expect(screen.getByRole('radio', { name: 'Depot' }).props.accessibilityState.checked).toBe(
      true,
    );
    await fireEvent.press(screen.getByRole('radio', { name: 'Cash' }));
    expect(onSelect).toHaveBeenCalledWith('cash');
  });

  it('exposes multi-choice chips as checkboxes', async () => {
    await renderWithIntl(
      <ChipGroup
        multi
        options={[{ value: 'a', label: 'A' }]}
        selected={['a']}
        onSelect={jest.fn()}
      />,
    );
    expect(screen.getByRole('checkbox', { name: 'A' }).props.accessibilityState.checked).toBe(true);
  });

  it('masks account balances even in the rendered tree', async () => {
    await renderWithIntl(<AccountRow title="Main" amount="1234567.89" currency="CHF" masked />);
    expect(screen.getByText('••••')).toBeTruthy();
    expect(JSON.stringify(screen.toJSON())).not.toContain('1234567.89');
  });

  it('reads title, amount and value status on a pressable row', async () => {
    await renderWithIntl(
      <AccountRow
        title="Main"
        subtitle="Cash"
        amount="-1234.5"
        currency="CHF"
        status="ESTIMATED"
        onPress={jest.fn()}
      />,
      'en',
    );
    const label = screen.getByRole('button', { name: /^Main/ }).props.accessibilityLabel;
    expect(label).toBe('Main, Cash, minus 1234.50 Swiss francs, Estimated');
  });

  it('offers the status explanation as an action, not as a button nested in the row', async () => {
    const onPress = jest.fn();
    await renderWithIntl(
      <AccountRow
        title="Main"
        amount="10"
        currency="CHF"
        status="STALE"
        valueSourceDate="2026-10-01"
        onPress={onPress}
      />,
    );
    // The row is the only button: the badge inside it is plain text.
    expect(screen.getAllByRole('button')).toHaveLength(1);
    const row = screen.getByRole('button', { name: /^Main/ });
    expect(row.props.accessibilityActions).toEqual([
      { name: 'activate' },
      { name: 'explainValueStatus', label: 'Explain value status' },
    ]);
    expect(screen.queryByText(/older than usual/)).toBeNull();
    await fireEvent(row, 'accessibilityAction', {
      nativeEvent: { actionName: 'explainValueStatus' },
    });
    expect(screen.getByText(/older than usual/)).toBeTruthy();
    expect(onPress).not.toHaveBeenCalled();
    await fireEvent(row, 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
    expect(onPress).toHaveBeenCalledTimes(1);
  });

  it('keeps the badge a button in a row that is not pressable', async () => {
    await renderWithIntl(<AccountRow title="Main" amount="10" currency="CHF" status="ESTIMATED" />);
    const badge = screen.getByRole('button', { name: /^Estimated\. / });
    await fireEvent.press(badge);
    expect(screen.getByText(/rests on an approximation/)).toBeTruthy();
  });

  it('adds no actions to a pressable row without a status badge', async () => {
    await renderWithIntl(
      <AccountRow title="Main" amount="10" currency="CHF" status="CURRENT" onPress={jest.fn()} />,
    );
    expect(
      screen.getByRole('button', { name: /^Main/ }).props.accessibilityActions,
    ).toBeUndefined();
  });

  it('keeps a hidden amount hidden in the pressable row label', async () => {
    await renderWithIntl(
      <AccountRow title="Main" amount="1234.5" currency="CHF" masked onPress={jest.fn()} />,
      'en',
    );
    const label = screen.getByRole('button', { name: /^Main/ }).props.accessibilityLabel;
    expect(label).toBe('Main, hidden');
  });

  it.each(['UNKNOWN', 'FUTURE_SERVER_ENUM'])(
    'shows the amount as "–" for status %s',
    async (status) => {
      const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
      await renderWithIntl(
        <AccountRow title="Main" amount="1234.5" currency="CHF" status={status} />,
        'en',
      );
      expect(screen.getByText('–')).toBeTruthy();
      expect(screen.queryByText(/1,234/)).toBeNull();
      warn.mockRestore();
    },
  );

  it('formats the transaction date in the UI locale', async () => {
    await renderWithIntl(
      <TransactionRow title="Coffee" date="2026-11-08" amount="-4.5" currency="CHF" />,
      'de-CH',
    );
    expect(screen.getByText('08.11.2026')).toBeTruthy();
  });

  it.each([
    ['-312.4', '↘', '#C42F37'],
    ['6842.1', '↗', '#0F7A3A'],
    ['-0.001', '→', '#53627A'],
    ['0', '→', '#53627A'],
  ])('shows change %s with arrow %s in the matching colour', async (amount, arrow, color) => {
    await renderWithIntl(<ChangeChip amount={amount} currency="CHF" />, 'de-CH');
    const glyph = screen.getByText(arrow, { includeHiddenElements: true });
    expect(glyph.props.style.color).toBe(color);
    expect(glyph.props.accessibilityElementsHidden).toBe(true);
  });

  it('keeps a hidden change neutral and drops its percentage', async () => {
    jest.mocked(useAmountsHidden).mockReturnValue(true);
    await renderWithIntl(<ChangeChip amount="-312.4" currency="CHF" percentage="−0.8%" />);
    expect(screen.getByText('••••')).toBeTruthy();
    expect(screen.queryByText('−0.8%')).toBeNull();
    const glyph = screen.getByText('→', { includeHiddenElements: true });
    expect(glyph.props.style.color).toBe('#53627A');
    jest.mocked(useAmountsHidden).mockReturnValue(false);
  });

  it('reads "hidden" in a pressable row while "Hide amounts" is on', async () => {
    jest.mocked(useAmountsHidden).mockReturnValue(true);
    await renderWithIntl(
      <AccountRow title="Main" amount="1234.5" currency="CHF" onPress={jest.fn()} />,
    );
    expect(screen.getByRole('button', { name: /^Main/ }).props.accessibilityLabel).toBe(
      'Main, hidden',
    );
    jest.mocked(useAmountsHidden).mockReturnValue(false);
  });

  it('falls back to institution initials when no logo is provided', async () => {
    await renderWithIntl(<InstitutionLogo name="Example Bank" />);
    expect(screen.getByText('EB')).toBeTruthy();
    expect(screen.getByRole('image', { name: 'Example Bank' })).toBeTruthy();
  });

  it('shows the house icon for the personal-assets default portfolio', async () => {
    await renderWithIntl(<InstitutionLogo name="Mein Vermögen" personalAssetsDefault />);
    expect(findHost(screen.toJSON(), 'SymbolView')).toBeTruthy();
    expect(screen.queryByText('MV')).toBeNull();
  });

  it('keeps the logo image out of the accessibility tree', async () => {
    await renderWithIntl(
      <InstitutionLogo name="Example Bank" logoUrl="https://logo.example/x.png" />,
    );
    expect(findHost(screen.toJSON(), 'Image')?.props.accessible).toBe(false);
  });

  it('lets completed steps be revisited but not future ones', async () => {
    const onSelect = jest.fn();
    await renderWithIntl(
      <Stepper steps={['Create', 'Review', 'Finish']} current={1} onSelect={onSelect} />,
      'en',
    );
    const finish = screen.getByRole('button', { name: '3. Finish' });
    expect(finish.props.accessibilityState.disabled).toBe(true);
    expect(
      screen.getByRole('button', { name: '2. Review' }).props.accessibilityState.selected,
    ).toBe(true);
    await fireEvent.press(screen.getByRole('button', { name: '1. Create' }));
    expect(onSelect).toHaveBeenCalledWith(0);
  });

  it('renders key/value pairs with text or custom content', async () => {
    await renderWithIntl(
      <KeyValueList
        items={[
          { key: 'a', label: 'Source', value: 'Statement' },
          { key: 'b', label: 'Count', value: 3 },
        ]}
      />,
    );
    expect(screen.getByText('Statement')).toBeTruthy();
    expect(screen.getByText('3')).toBeTruthy();
  });
});

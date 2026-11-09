import { fireEvent, render, screen } from '@testing-library/react-native';

import { AccountRow, ChipGroup, InstitutionLogo, PortfolioTabs } from './presentation';

jest.mock('@/global.css', () => ({}));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => ({
    textPrimary: '#0B1C3F', textSecondary: '#53627A', textOnBrand: '#FFFFFF',
    bgSurface: '#FFFFFF', bgSubtle: '#ECF1F4', bgInverse: '#0B1C3F',
    borderDefault: '#E1E7EC', borderInteractive: '#64758A',
    brandSubtle: '#E0F5F1', brandLink: '#006D64', statusInfo: '#1F5CC4',
    statusInfoSubtle: '#E6EFFF', statusWarning: '#8F5200', statusWarningSubtle: '#FFF3D9',
  }),
}));
jest.mock('expo-image', () => ({ Image: 'Image' }));

describe('shared navigation and financial rows', () => {
  it('tracks selected portfolio tabs and exposes accessible states', () => {
    const onSelect = jest.fn();
    render(<PortfolioTabs tabs={[{ id: 'a', label: 'All' }, { id: 'b', label: 'Bank' }]}
      selected="a" onSelect={onSelect} />);
    expect(screen.getByRole('tab', { name: 'All' }).props.accessibilityState.selected).toBe(true);
    fireEvent.press(screen.getByRole('tab', { name: 'Bank' }));
    expect(onSelect).toHaveBeenCalledWith('b');
  });
  it('calls the chip control with the selected filter', () => {
    const onSelect = jest.fn();
    render(<ChipGroup options={[{ value: 'cash', label: 'Cash' }]} selected={[]} onSelect={onSelect} />);
    fireEvent.press(screen.getByRole('radio', { name: 'Cash' }));
    expect(onSelect).toHaveBeenCalledWith('cash');
  });
  it('masks account balances even in the rendered tree', () => {
    render(<AccountRow title="Main" amount="1234567.89" currency="CHF" masked />);
    expect(screen.getByText('••••')).toBeTruthy();
    expect(JSON.stringify(screen.toJSON())).not.toContain('1234567.89');
  });
  it('falls back to institution initials when no logo is provided', () => {
    render(<InstitutionLogo name="Example Bank" />);
    expect(screen.getByText('EB')).toBeTruthy();
  });
});

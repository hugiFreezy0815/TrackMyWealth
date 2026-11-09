import { fireEvent, render, screen } from '@testing-library/react-native';

import { ValueStatusBadge } from './value-status';

jest.mock('@/global.css', () => ({}));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => ({
    textPrimary: '#0B1C3F',
    textSecondary: '#53627A',
    statusWarning: '#8F5200',
    statusWarningSubtle: '#FFF3D9',
    statusInfo: '#1F5CC4',
    statusInfoSubtle: '#E6EFFF',
    bgSubtle: '#ECF1F4',
    bgSurface: '#FFFFFF',
    brandLink: '#006D64',
  }),
}));
describe('ValueStatusBadge', () => {
  it('renders nothing for CURRENT', async () => {
    const { toJSON } = await render(<ValueStatusBadge status="CURRENT" />);
    expect(toJSON()).toBeNull();
  });
  it.each([
    ['ESTIMATED', 'Estimated'],
    ['STALE', 'Price from 2026-10-01'],
    ['UNRECONCILED', 'Differs from statement'],
    ['UNKNOWN', 'Value unknown'],
    ['FUTURE_SERVER_ENUM', 'Value unknown'],
    ['toString', 'Value unknown'],
  ])('renders %s as %s', async (status, label) => {
    await render(<ValueStatusBadge status={status} valueSourceDate="2026-10-01" locale="en" />);
    expect(screen.getByRole('button', { name: new RegExp(label) })).toBeTruthy();
  });
  it('explains the financial status on demand', async () => {
    await render(<ValueStatusBadge status="ESTIMATED" locale="en" />);
    await fireEvent.press(screen.getByRole('button', { name: /Estimated/ }));
    expect(screen.getByText(/based on an approximation/)).toBeTruthy();
  });
});

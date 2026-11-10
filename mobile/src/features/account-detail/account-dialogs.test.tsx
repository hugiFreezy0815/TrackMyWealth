import { fireEvent, screen } from '@testing-library/react-native';

import { renderWithIntl } from '@/i18n/test-utils';

import { ActionsDialog } from './account-dialogs';

jest.mock('@/global.css', () => ({}));

describe('account action permissions', () => {
  it('disables import offline and explains why in the accessible control', async () => {
    const onImport = jest.fn();
    await renderWithIntl(
      <ActionsDialog
        visible
        archived={false}
        online={false}
        onClose={jest.fn()}
        onImport={onImport}
        onArchive={jest.fn()}
      />,
    );
    const importButton = screen.getByRole('button', { name: 'Import statement' });
    expect(importButton).toBeDisabled();
    expect(importButton.props.accessibilityHint).toBe(
      'Connect to the internet to import a statement.',
    );
    expect(screen.getByText('Connect to the internet to import a statement.')).toBeTruthy();
    expect(onImport).not.toHaveBeenCalled();
  });

  it('enables import for an active account while online', async () => {
    const onImport = jest.fn();
    await renderWithIntl(
      <ActionsDialog
        visible
        archived={false}
        online
        onClose={jest.fn()}
        onImport={onImport}
        onArchive={jest.fn()}
      />,
    );
    await fireEvent.press(screen.getByRole('button', { name: 'Import statement' }));
    expect(onImport).toHaveBeenCalledTimes(1);
  });

  it('does not allow archived accounts to import even while online', async () => {
    await renderWithIntl(
      <ActionsDialog
        visible
        archived
        online
        onClose={jest.fn()}
        onImport={jest.fn()}
        onArchive={jest.fn()}
      />,
    );
    expect(screen.getByRole('button', { name: 'Import statement' })).toBeDisabled();
  });
});

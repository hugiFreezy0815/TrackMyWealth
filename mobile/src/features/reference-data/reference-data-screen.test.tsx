import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import { ApiError, NetworkError } from '@/api/client';
import { type Identity } from '@/features/auth/session';

import { loadReferenceData } from './reference-data';
import { friendlyDate, ReferenceDataScreen } from './reference-data-screen';

jest.mock('@/global.css', () => ({}));
jest.mock('./reference-data', () => ({ loadReferenceData: jest.fn() }));

const ADMIN: Identity = {
  userId: 'admin',
  role: 'SYSTEM_ADMINISTRATOR',
  language: 'EN',
  sessionId: 's1',
};
const MEMBER: Identity = {
  userId: 'member',
  role: 'STANDARD_USER',
  language: 'EN',
  sessionId: 's2',
};

const RESPONSE = {
  packageVersion: '1.7.0-baseline',
  publicationDate: '2026-10-16',
  importedAt: '2026-10-16T13:00:00Z',
  importedBy: null,
  contents: {
    catalogueInstitutions: 12,
    defaultCategories: 14,
    sourceCodeMappings: 20,
    fallbackSectors: 5,
    gicsStructureVersion: '2023',
  },
  stalenessWarnings: [],
};

describe('ReferenceDataScreen', () => {
  // Reset, not clear: a queued mockResolvedValueOnce a failed test left unused must not leak on.
  beforeEach(() => jest.resetAllMocks());

  it('loads and shows package metadata, counts and no warnings to the administrator', async () => {
    jest.mocked(loadReferenceData).mockResolvedValueOnce(RESPONSE);
    await render(<ReferenceDataScreen identity={ADMIN} />);

    expect(await screen.findByText('Version: 1.7.0-baseline')).toBeTruthy();
    expect(screen.getByText(`Published: ${friendlyDate('2026-10-16')}`)).toBeTruthy();
    expect(screen.getByText('Imported by: Application baseline')).toBeTruthy();
    expect(screen.getByText('Catalogue institutions: 12')).toBeTruthy();
    expect(screen.getByText('Default categories: 14')).toBeTruthy();
    expect(screen.getByText('Source-code mappings: 20')).toBeTruthy();
    expect(screen.getByText('Fallback sectors: 5')).toBeTruthy();
    expect(screen.getByText('GICS structure: 2023')).toBeTruthy();
    expect(screen.getByText('No staleness warnings.')).toBeTruthy();
    expect(loadReferenceData).toHaveBeenCalledTimes(1);
  });

  it('does not request or reveal administrative data for a member or unverified identity', async () => {
    const view = await render(<ReferenceDataScreen identity={MEMBER} />);

    expect(screen.getByText('Reference data is not available.')).toBeTruthy();
    expect(loadReferenceData).not.toHaveBeenCalled();

    await view.rerender(<ReferenceDataScreen identity={null} />);
    expect(screen.getByText('Reference data is not available.')).toBeTruthy();
    expect(loadReferenceData).not.toHaveBeenCalled();
  });

  it('shows a generic unavailable response to a backend 403', async () => {
    jest
      .mocked(loadReferenceData)
      .mockRejectedValueOnce(
        new ApiError('sensitive backend error details', 403, { internal: 'hidden' }),
      );
    await render(<ReferenceDataScreen identity={ADMIN} />);

    expect(await screen.findByText('Reference data is not available.')).toBeTruthy();
    expect(screen.queryByText(/sensitive backend error details/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
  });

  it('offers retry after network failure and shows the recovered data', async () => {
    jest
      .mocked(loadReferenceData)
      .mockRejectedValueOnce(new NetworkError('network unavailable'))
      .mockResolvedValueOnce(RESPONSE);
    await render(<ReferenceDataScreen identity={ADMIN} />);

    expect(await screen.findByRole('alert')).toHaveTextContent(/Could not load reference data/);
    await fireEvent.press(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() => expect(loadReferenceData).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('Version: 1.7.0-baseline')).toBeTruthy();
  });

  it('displays structured staleness warnings without losing count data', async () => {
    jest.mocked(loadReferenceData).mockResolvedValueOnce({
      ...RESPONSE,
      importedBy: '00000000-0000-0000-0000-000000000001',
      stalenessWarnings: [
        { code: 'LIMIT_MISSING', subject: 'CH_PILLAR_3A', missingPeriod: '2027' },
      ],
    });
    await render(<ReferenceDataScreen identity={ADMIN} />);
    expect(await screen.findByText('LIMIT MISSING: CH_PILLAR_3A (2027)')).toBeTruthy();
    expect(screen.getByText('Catalogue institutions: 12')).toBeTruthy();
  });

  it('forgets loaded data when the session loses the administrator role', async () => {
    jest.mocked(loadReferenceData).mockResolvedValueOnce(RESPONSE);
    const view = await render(<ReferenceDataScreen identity={ADMIN} />);
    expect(await screen.findByText('Version: 1.7.0-baseline')).toBeTruthy();

    await view.rerender(<ReferenceDataScreen identity={MEMBER} />);
    expect(screen.queryByText('Version: 1.7.0-baseline')).toBeNull();

    // Back as administrator, the screen loads afresh instead of showing what it held before.
    jest.mocked(loadReferenceData).mockReturnValueOnce(new Promise(() => {}));
    await view.rerender(<ReferenceDataScreen identity={ADMIN} />);
    expect(screen.getByText('Loading reference data…')).toBeTruthy();
    expect(screen.queryByText('Version: 1.7.0-baseline')).toBeNull();
  });
});

describe('friendlyDate', () => {
  it('keeps a calendar date on its day in any time zone', () => {
    expect(friendlyDate('2026-10-16')).toBe(
      new Date(Date.UTC(2026, 9, 16)).toLocaleDateString(undefined, { timeZone: 'UTC' }),
    );
    expect(friendlyDate('not-a-date')).toBe('Not available');
  });
});

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { IntlProvider, useIntl } from 'react-intl';

import { ApiError } from '@/api/client';
import { queryKeys } from '@/api/queries/keys';
import { useWorkspace } from '@/api/queries/resources';
import { typedApi } from '@/api/typed-api';
import type { components } from '@/api/schema';
import { TabStrip } from '@/features/overview/tab-strip';
import en from '@/i18n/en.json';

import { OverviewNameEditor } from './overview-name-editor';

jest.mock('@/global.css', () => ({}));
jest.mock('@/api/typed-api', () => ({ typedApi: { get: jest.fn(), put: jest.fn() } }));

type Workspace = components['schemas']['WorkspaceResponse'];
const workspace: Workspace = { id: 'w-1', name: null, currency: 'CHF', version: 3 };

/** The overview's real first tab, fed from the same workspace cache as the overview screen. */
function FirstTab() {
  const intl = useIntl();
  const current = useWorkspace();
  return (
    <TabStrip
      name={current.data?.name ?? null}
      intl={intl}
      offline={false}
      onAddPortfolio={() => {}}
    />
  );
}

async function setup(
  mode: 'settings' | 'setup' = 'settings',
  current: Workspace = workspace,
  withTab = false,
) {
  const client = new QueryClient({
    defaultOptions: {
      // gcTime: Infinity, or GC timers keep a single-file jest run alive after it passes.
      queries: { retry: false, gcTime: Infinity },
      mutations: { retry: false, gcTime: Infinity },
    },
  });
  client.setQueryData(queryKeys.workspace, current);
  const onFinished = jest.fn();
  const onSkip = jest.fn();
  const ui = await render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={en}>
        {withTab ? <FirstTab /> : null}
        <OverviewNameEditor
          mode={mode}
          workspace={current}
          onFinished={onFinished}
          onSkip={onSkip}
        />
      </IntlProvider>
    </QueryClientProvider>,
  );
  return { ...ui, client, onFinished, onSkip };
}

beforeEach(() => {
  jest.resetAllMocks();
  jest.mocked(typedApi.put).mockImplementation(async () => workspace as never);
  jest.mocked(typedApi.get).mockImplementation(async () => workspace as never);
});

describe('overview-name editor (#490)', () => {
  it('shows a live first-tab preview and disables Save past 60 characters', async () => {
    await setup();
    expect(screen.getByRole('tab', { name: 'All portfolios' })).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Name of your overview'), 'Family Hug');
    expect(screen.getByRole('tab', { name: 'Family Hug' })).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Name of your overview'), 'a'.repeat(61));
    expect(screen.getByText('Use at most 60 characters.')).toBeTruthy();
    expect(
      screen.getByRole('button', { name: 'Save' }).props.accessibilityState?.disabled ??
        screen.getByRole('button', { name: 'Save' }).props['aria-disabled'],
    ).toBe(true);
    await fireEvent.press(screen.getByRole('button', { name: 'Save' }));
    expect(typedApi.put).not.toHaveBeenCalled();
  });

  it('sends the typed name with the version and immediately updates the cached tab', async () => {
    jest.mocked(typedApi.put).mockResolvedValueOnce({
      ...workspace,
      name: 'Family Hug',
      version: 4,
    } as never);
    const { client, onFinished } = await setup();
    await fireEvent.changeText(screen.getByLabelText('Name of your overview'), '  Family Hug  ');
    await fireEvent.press(screen.getByRole('button', { name: 'Save' }));
    expect(typedApi.put).toHaveBeenCalledWith('/api/v1/workspace/name', {
      version: 3,
      body: { name: 'Family Hug' },
    });
    expect(client.getQueryData<Workspace>(queryKeys.workspace)?.name).toBe('Family Hug');
    expect(onFinished).toHaveBeenCalledTimes(1);
  });

  it('clears the saved name with explicit null and immediately restores the fallback', async () => {
    const named = { ...workspace, name: 'Family Hug', version: 7 };
    jest.mocked(typedApi.put).mockResolvedValueOnce({ ...named, name: null, version: 8 } as never);
    const { client } = await setup('settings', named);
    await fireEvent.press(screen.getByRole('button', { name: 'Clear name' }));
    expect(typedApi.put).toHaveBeenCalledWith('/api/v1/workspace/name', {
      version: 7,
      body: { name: null },
    });
    expect(client.getQueryData<Workspace>(queryKeys.workspace)?.name).toBeNull();
  });

  it('shows the field-specific validation error without exposing raw server detail', async () => {
    jest.mocked(typedApi.put).mockRejectedValueOnce(
      new ApiError('server raw detail', 400, {
        status: 400,
        title: 'bad request',
        code: 'VALIDATION_FAILED',
        errors: [{ field: 'name', message: 'server raw detail' }],
      }),
    );
    await setup();
    await fireEvent.changeText(screen.getByLabelText('Name of your overview'), 'Invalid');
    await fireEvent.press(screen.getByRole('button', { name: 'Save' }));
    expect(
      await screen.findByText('This name cannot be saved. Please choose another.'),
    ).toBeTruthy();
    expect(screen.queryByText('server raw detail')).toBeNull();
  });

  it("reloads a conflicting name and keeps the member's typed name intact", async () => {
    jest.mocked(typedApi.put).mockRejectedValueOnce(
      new ApiError('stale', 412, {
        status: 412,
        title: 'conflict',
        code: 'VERSION_CONFLICT',
      }),
    );
    jest.mocked(typedApi.get).mockResolvedValueOnce({
      ...workspace,
      name: 'Their name',
      version: 4,
    } as never);
    await setup();
    await fireEvent.changeText(screen.getByLabelText('Name of your overview'), 'My name');
    await fireEvent.press(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Another member changed the name to: Their name')).toBeTruthy();
    expect(screen.getByText('Your unsaved name: My name')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Review and save again' }));
    expect(screen.getByLabelText('Name of your overview').props.value).toBe('My name');
    expect(typedApi.get).toHaveBeenCalledWith('/api/v1/workspace');
  });

  it('saves currency first and uses its returned version for the optional name', async () => {
    jest
      .mocked(typedApi.put)
      .mockResolvedValueOnce({ ...workspace, currency: 'EUR', version: 4 } as never)
      .mockResolvedValueOnce({
        ...workspace,
        name: 'My money',
        currency: 'EUR',
        version: 5,
      } as never);
    const { onFinished } = await setup('setup');
    await fireEvent.press(screen.getByRole('radio', { name: 'EUR' }));
    await fireEvent.changeText(screen.getByLabelText('Name of your overview'), 'My money');
    await fireEvent.press(screen.getByRole('button', { name: 'Continue' }));
    expect(typedApi.put).toHaveBeenNthCalledWith(1, '/api/v1/workspace/currency', {
      version: 3,
      body: { currency: 'EUR' },
    });
    expect(typedApi.put).toHaveBeenNthCalledWith(2, '/api/v1/workspace/name', {
      version: 4,
      body: { name: 'My money' },
    });
    expect(onFinished).toHaveBeenCalledTimes(1);
  });

  it('shows the saved name in the first tab right after Save, without a reload', async () => {
    jest.mocked(typedApi.put).mockResolvedValueOnce({
      ...workspace,
      name: 'Family Hug',
      version: 4,
    } as never);
    await setup('settings', workspace, true);
    expect(screen.getAllByRole('tab', { name: 'All portfolios' })).toHaveLength(2);
    await fireEvent.changeText(screen.getByLabelText('Name of your overview'), 'Family Hug');
    // Only the editor's preview follows the typing; the overview tab still shows the saved state.
    expect(screen.getAllByRole('tab', { name: 'Family Hug' })).toHaveLength(1);
    await fireEvent.press(screen.getByRole('button', { name: 'Save' }));
    // The PUT response replaces the cached workspace, so the overview tab updates with no GET.
    await waitFor(() => expect(screen.getAllByRole('tab', { name: 'Family Hug' })).toHaveLength(2));
    expect(screen.queryByRole('tab', { name: 'All portfolios' })).toBeNull();
  });

  it('clears the saved name when Settings is saved with an empty field', async () => {
    const named = { ...workspace, name: 'Family Hug', version: 7 };
    jest.mocked(typedApi.put).mockResolvedValueOnce({ ...named, name: null, version: 8 } as never);
    const { client, onFinished } = await setup('settings', named);
    await fireEvent.changeText(screen.getByLabelText('Name of your overview'), '   ');
    await fireEvent.press(screen.getByRole('button', { name: 'Save' }));
    expect(typedApi.put).toHaveBeenCalledWith('/api/v1/workspace/name', {
      version: 7,
      body: { name: null },
    });
    expect(client.getQueryData<Workspace>(queryKeys.workspace)?.name).toBeNull();
    expect(onFinished).toHaveBeenCalledTimes(1);
  });

  it('closes without a request when the name did not change', async () => {
    const named = { ...workspace, name: 'Family Hug', version: 7 };
    const { onFinished } = await setup('settings', named);
    await fireEvent.changeText(screen.getByLabelText('Name of your overview'), ' Family Hug ');
    await fireEvent.press(screen.getByRole('button', { name: 'Save' }));
    expect(typedApi.put).not.toHaveBeenCalled();
    expect(onFinished).toHaveBeenCalledTimes(1);
  });

  it('accepts 60 emoji and counts them as 60 characters, as the server does', async () => {
    await setup();
    await fireEvent.changeText(screen.getByLabelText('Name of your overview'), '🏠'.repeat(60));
    expect(screen.getByText('60 of 60 characters')).toBeTruthy();
    expect(screen.queryByText('Use at most 60 characters.')).toBeNull();
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
  });

  it('shows another 409 as an error, not as a rename by another member', async () => {
    jest
      .mocked(typedApi.put)
      .mockRejectedValueOnce(
        new ApiError('conflict', 409, { status: 409, title: 'Conflict', code: 'CONFLICT' }),
      );
    await setup();
    await fireEvent.changeText(screen.getByLabelText('Name of your overview'), 'My name');
    await fireEvent.press(screen.getByRole('button', { name: 'Save' }));
    expect(screen.queryByText(/Another member changed the name/)).toBeNull();
    expect(typedApi.get).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Name of your overview').props.value).toBe('My name');
  });

  it('skips without making any API changes', async () => {
    const { onSkip } = await setup('setup');
    await fireEvent.press(screen.getByRole('button', { name: 'Skip for now' }));
    expect(onSkip).toHaveBeenCalledTimes(1);
    expect(typedApi.put).not.toHaveBeenCalled();
  });
});

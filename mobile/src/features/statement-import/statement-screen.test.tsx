import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider, onlineManager } from '@tanstack/react-query';
import * as DocumentPicker from 'expo-document-picker';
import { IntlProvider } from 'react-intl';

import { invalidateFinancialData } from '@/api/queries/invalidation';
import { useAccount, useAccounts, usePortfolios } from '@/api/queries/resources';
import { typedApi } from '@/api/typed-api';
import en from '@/i18n/en.json';

import { deletePickedCopy, shareErrorsCsv } from './device-files';
import { FakeImportServer, problem, syntheticBatch, syntheticRow } from './fake-import-server';
import type { Batch, ImportRow } from './model';
import { StatementImportScreen } from './statement-screen';
import { SLOW_UPLOAD_MS } from './use-statement-import';

let mockHidden = false;

jest.mock('@/global.css', () => ({}));
jest.mock('expo-document-picker', () => ({ getDocumentAsync: jest.fn() }));
jest.mock('./device-files', () => ({
  deletePickedCopy: jest.fn(),
  shareErrorsCsv: jest.fn(async () => {}),
}));
jest.mock('@/api/typed-api', () => ({
  typedApi: {
    get: jest.fn(),
    post: jest.fn(),
    patch: jest.fn(),
    upload: jest.fn(),
    text: jest.fn(),
  },
}));
jest.mock('@/api/queries/invalidation', () => ({
  invalidateFinancialData: jest.fn(async () => {}),
}));
jest.mock('@/features/privacy/amount-visibility', () => ({
  useAmountsHidden: () => mockHidden,
}));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => ({
    textPrimary: '#0B1C3F',
    textSecondary: '#53627A',
    bgSurface: '#FFFFFF',
    borderDefault: '#E1E7EC',
    statusWarning: '#8F5200',
    statusLoss: '#C42F37',
    brandLink: '#006D64',
    brandPrimary: '#00857A',
    textOnBrand: '#FFFFFF',
  }),
}));
jest.mock('@/api/queries/resources', () => ({
  useAccount: jest.fn(),
  useAccounts: jest.fn(),
  usePortfolios: jest.fn(),
}));
jest.mock('@/components/shared', () => {
  const React = require('react');
  const { Text, View, Pressable } = require('react-native');
  const button = (label: string, onPress: () => void, disabled?: boolean) =>
    React.createElement(
      Pressable,
      {
        accessibilityRole: 'button',
        accessibilityLabel: label,
        accessibilityState: { disabled: !!disabled },
        disabled,
        onPress,
      },
      React.createElement(Text, {}, label),
    );
  const amountText = (value: string, currency: string, masked?: boolean) =>
    masked ? '••••' : `${currency} ${value}`;
  return {
    SectionCard: ({ title, children }: { title: string; children: React.ReactNode }) =>
      React.createElement(View, {}, React.createElement(Text, {}, title), children),
    ActionButton: ({
      label,
      disabled,
      onPress,
    }: {
      label: string;
      disabled?: boolean;
      onPress: () => void;
    }) => button(label, onPress, disabled),
    Amount: ({ value, currency }: { value: string; currency: string }) =>
      React.createElement(Text, {}, amountText(value, currency)),
    amountTexts: (
      _intl: unknown,
      { value, currency, masked }: { value: string; currency: string; masked?: boolean },
    ) => ({ display: amountText(value, currency, masked), label: amountText(value, currency) }),
    ChipGroup: ({
      options,
      onSelect,
    }: {
      options: { value: string; label: string }[];
      onSelect: (value: string) => void;
    }) =>
      React.createElement(
        View,
        {},
        ...options.map((option) =>
          React.createElement(
            Pressable,
            {
              key: option.value,
              accessibilityRole: 'radio',
              accessibilityLabel: option.label,
              onPress: () => onSelect(option.value),
            },
            React.createElement(Text, {}, option.label),
          ),
        ),
      ),
    CountTile: ({ label, count }: { label: string; count: number }) =>
      React.createElement(Text, {}, `${label} ${count}`),
    Dialog: ({ visible, children }: { visible: boolean; children: React.ReactNode }) =>
      visible ? React.createElement(View, {}, children) : null,
    ConflictDialog: ({ visible, onReview }: { visible: boolean; onReview: () => void }) =>
      visible
        ? React.createElement(
            View,
            {},
            React.createElement(Text, {}, 'conflict'),
            button('Review', onReview),
          )
        : null,
    EmptyState: ({ title }: { title: string }) => React.createElement(Text, {}, title),
    ErrorBanner: ({ message, onRetry }: { message?: string; onRetry?: () => void }) =>
      React.createElement(
        View,
        {},
        React.createElement(Text, {}, message ?? 'error'),
        onRetry ? button('Try again', onRetry) : null,
      ),
    LoadingSkeleton: () => null,
    OfflineBanner: () => React.createElement(Text, {}, 'offline'),
  };
});

const account = {
  id: 'a-1',
  portfolioId: 'p-1',
  name: 'DKB account',
  accountType: 'CASH',
  nativeCurrency: 'EUR',
  hasTransactions: true,
};

let server: FakeImportServer;
let client: QueryClient;

function serve(batch: Batch | null, rows: ImportRow[] = []) {
  server = new FakeImportServer(batch, rows);
  jest.mocked(typedApi.get).mockImplementation(server.get as never);
  jest.mocked(typedApi.post).mockImplementation(server.post as never);
  jest.mocked(typedApi.patch).mockImplementation(server.patch as never);
  jest.mocked(typedApi.upload).mockImplementation(server.upload as never);
  jest.mocked(typedApi.text).mockImplementation(server.text as never);
  return server;
}

/** 41 new, 5 duplicates and 2 errors on the server; rows 1-3 are listed. */
function reviewFixture(changes: Partial<Batch> = {}, rows?: ImportRow[]) {
  return serve(
    syntheticBatch(changes),
    rows ?? [
      syntheticRow(1, 'PARSED'),
      syntheticRow(2, 'DUPLICATE', {
        duplicateOf: {
          bookingDate: '2026-11-08',
          amount: '-12.50',
          currency: 'EUR',
          merchantDescription: 'Synthetic shop',
        },
      }),
      syntheticRow(3, 'ERROR', {
        canonical: null,
        error: { code: 'IMPORT_ROW_DATE_INVALID', args: {}, message: 'The date cannot be read.' },
      }),
    ],
  );
}

async function mount(props: { accountId?: string; initialBatchId?: string } = {}) {
  const onShowAccount = jest.fn();
  const onExit = jest.fn();
  await render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={en}>
        <StatementImportScreen
          accountId={'accountId' in props ? props.accountId : 'a-1'}
          initialBatchId={props.initialBatchId}
          onShowAccount={onShowAccount}
          onExit={onExit}
        />
      </IntlProvider>
    </QueryClientProvider>,
  );
  return { onShowAccount, onExit };
}

function pickFile(asset: Partial<DocumentPicker.DocumentPickerAsset> = {}) {
  jest.mocked(DocumentPicker.getDocumentAsync).mockResolvedValue({
    canceled: false,
    assets: [
      {
        name: 'synthetic-dkb.csv',
        uri: 'file:///cache/synthetic-dkb.csv',
        size: 140,
        mimeType: 'text/csv',
        lastModified: 0,
        ...asset,
      },
    ],
  } as Awaited<ReturnType<typeof DocumentPicker.getDocumentAsync>>);
}

const commitButton = (count: number) =>
  screen.findByRole('button', {
    name: count === 1 ? 'Import 1 transaction' : `Import ${count} transactions`,
  });

beforeEach(() => {
  jest.clearAllMocks();
  mockHidden = false;
  onlineManager.setOnline(true);
  client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity, staleTime: 30_000 },
      mutations: { gcTime: Infinity },
    },
  });
  jest.mocked(useAccount).mockReturnValue({
    data: account,
    isPending: false,
    isError: false,
  } as unknown as ReturnType<typeof useAccount>);
  jest.mocked(useAccounts).mockReturnValue({
    data: [],
    isPending: false,
    isError: false,
    isSuccess: true,
  } as unknown as ReturnType<typeof useAccounts>);
  jest.mocked(usePortfolios).mockReturnValue({
    data: [{ id: 'p-1', name: 'DKB' }],
  } as unknown as ReturnType<typeof usePortfolios>);
  serve(null);
});

afterEach(() => {
  client.clear();
});

describe('S7 step 1: file and account (#492)', () => {
  it('uploads a synthetic DKB CSV, goes straight to the review and deletes the cached copy', async () => {
    pickFile();
    await mount();
    await fireEvent.press(screen.getByRole('button', { name: 'Choose file' }));

    expect(await commitButton(41)).toBeTruthy();
    expect(server.callsOf('upload', '/api/v1/accounts/{accountId}/imports')).toHaveLength(1);
    expect(server.callsOf('post', '/api/v1/accounts/{accountId}/imports/{batchId}/commit')).toEqual(
      [],
    );
    expect(deletePickedCopy).toHaveBeenCalledWith('file:///cache/synthetic-dkb.csv');
    // The upload's answer is the batch: no second read of it.
    expect(server.callsOf('get', '/api/v1/accounts/{accountId}/imports/{batchId}')).toEqual([]);
  });

  it('refuses a file over 5 MB on the device without uploading it', async () => {
    pickFile({ name: 'big.csv', size: 5 * 1024 * 1024 + 1 });
    await mount();
    await fireEvent.press(screen.getByRole('button', { name: 'Choose file' }));

    expect(
      await screen.findByText('This file is larger than 5 MB. Choose a smaller file.'),
    ).toBeTruthy();
    expect(server.calls).toEqual([]);
    expect(deletePickedCopy).toHaveBeenCalledWith('file:///cache/synthetic-dkb.csv');
  });

  it('shows both template candidates and parses with the chosen one and the batch version', async () => {
    pickFile();
    serve(null).uploadAnswer = syntheticBatch({
      status: 'UPLOADED',
      templateId: null,
      version: 0,
      templateCandidates: [
        { template: { id: 't-dkb', name: 'DKB Girokonto' } as never, match: 'HEADER' },
        { template: { id: 't-dkb-old', name: 'DKB (2019)' } as never, match: 'HEADER' },
      ],
    });
    await mount();
    await fireEvent.press(screen.getByRole('button', { name: 'Choose file' }));

    await fireEvent.press(await screen.findByRole('radio', { name: 'DKB (2019) · HEADER' }));
    expect(screen.getByRole('radio', { name: 'DKB Girokonto · HEADER' })).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Read file' }));

    expect(await commitButton(41)).toBeTruthy();
    const [parse] = server.callsOf('post', '/api/v1/accounts/{accountId}/imports/{batchId}/parse');
    expect(parse.options).toMatchObject({ version: 0, body: { templateId: 't-dkb-old' } });
  });

  it('lists the unfinished imports on IMPORT_TOO_MANY_OPEN_BATCHES and continues one', async () => {
    pickFile();
    serve(null).failNext(
      'upload',
      '/api/v1/accounts/{accountId}/imports',
      problem('IMPORT_TOO_MANY_OPEN_BATCHES', 409),
    );
    server.open = [syntheticBatch({ id: 'b-open', sourceFileName: 'older.csv', version: 7 })];
    server.batch = server.open[0];
    await mount();
    await fireEvent.press(screen.getByRole('button', { name: 'Choose file' }));

    expect(
      await screen.findByText(
        'You have unfinished imports for this account. Continue or discard one.',
      ),
    ).toBeTruthy();
    expect(await screen.findByText('older.csv')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Continue' }));

    expect(await commitButton(41)).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Import 41 transactions' }));
    await waitFor(() =>
      expect(
        server.callsOf('post', '/api/v1/accounts/{accountId}/imports/{batchId}/commit')[0]?.options,
      ).toMatchObject({ params: { batchId: 'b-open' }, version: 7 }),
    );
  });

  it('discards an unfinished import from the list with its own version', async () => {
    serve(null).open = [syntheticBatch({ id: 'b-old', sourceFileName: 'old.csv', version: 9 })];
    await mount();
    await fireEvent.press(screen.getByRole('button', { name: 'Continue unfinished imports' }));
    await screen.findByText('old.csv');
    await fireEvent.press(screen.getByRole('button', { name: 'Discard' }));

    await waitFor(() => expect(screen.queryByText('old.csv')).toBeNull());
    const [discard] = server.callsOf(
      'post',
      '/api/v1/accounts/{accountId}/imports/{batchId}/discard',
    );
    expect(discard.options).toMatchObject({ params: { batchId: 'b-old' }, version: 9 });
  });

  it("counts down a busy parser's Retry-After before offering Retry, keeping the file", async () => {
    pickFile();
    serve(null).failNext(
      'upload',
      '/api/v1/accounts/{accountId}/imports',
      problem('IMPORT_PDF_BUSY', 503, 1),
    );
    await mount();
    await fireEvent.press(screen.getByRole('button', { name: 'Choose file' }));

    expect(await screen.findByText('PDF processing is busy. Try again in a moment.')).toBeTruthy();
    expect(screen.getByText('You can try again in 1 second.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
    // Kept for the retry.
    expect(deletePickedCopy).not.toHaveBeenCalled();

    await fireEvent.press(
      await screen.findByRole('button', { name: 'Try again' }, { timeout: 2500 }),
    );
    expect(await commitButton(41)).toBeTruthy();
    expect(server.callsOf('upload', '/api/v1/accounts/{accountId}/imports')).toHaveLength(2);
    expect(deletePickedCopy).toHaveBeenCalledWith('file:///cache/synthetic-dkb.csv');
  });

  it('says a long upload takes longer and lets the member cancel it', async () => {
    jest.useFakeTimers();
    try {
      pickFile();
      serve(null).hangUploads = true;
      await mount();
      await fireEvent.press(screen.getByRole('button', { name: 'Choose file' }));

      expect(await screen.findByText('Reading synthetic-dkb.csv…')).toBeTruthy();
      expect(screen.queryByText('Scanned statements take longer.')).toBeNull();
      await act(async () => {
        jest.advanceTimersByTime(SLOW_UPLOAD_MS);
      });
      expect(screen.getByText('Scanned statements take longer.')).toBeTruthy();

      await fireEvent.press(screen.getByRole('button', { name: 'Cancel upload' }));
      expect(await screen.findByRole('button', { name: 'Choose file' })).toBeTruthy();
      expect(screen.queryByText('error')).toBeNull();
      expect(deletePickedCopy).toHaveBeenCalledWith('file:///cache/synthetic-dkb.csv');
    } finally {
      jest.useRealTimers();
    }
  });

  it('shows the holdings "coming soon" message with Discard and no review', async () => {
    serve(syntheticBatch({ holdings: {} as Batch['holdings'] }));
    await mount({ initialBatchId: 'b-1' });

    expect(
      await screen.findByText(
        'This is a holdings statement. Importing holdings in the app is coming soon.',
      ),
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Import \d+ transactions?$/ })).toBeNull();
  });

  it('treats an unknown batch status as not committable', async () => {
    serve(syntheticBatch({ status: 'SPLIT' }));
    await mount({ initialBatchId: 'b-1' });

    expect(await screen.findByText('Update the app to finish this import.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Import \d+ transactions?$/ })).toBeNull();
  });

  it('disables the upload while offline', async () => {
    onlineManager.setOnline(false);
    await mount();

    expect(screen.getByRole('button', { name: 'Choose file' })).toBeDisabled();
  });
});

describe('S7 web entry: account picker (#492)', () => {
  it('lists only active accounts that keep transactions', async () => {
    jest.mocked(useAccounts).mockReturnValue({
      data: [
        { id: 'a-1', name: 'Giro', portfolioName: 'DKB', archived: false, hasTransactions: true },
        {
          id: 'a-2',
          name: 'FZ',
          portfolioName: 'Pension',
          archived: false,
          hasTransactions: false,
        },
        { id: 'a-3', name: 'Old', portfolioName: 'DKB', archived: true, hasTransactions: true },
      ],
      isPending: false,
      isError: false,
      isSuccess: true,
    } as unknown as ReturnType<typeof useAccounts>);
    await mount({ accountId: undefined });

    expect(screen.getByRole('radio', { name: 'DKB · Giro' })).toBeTruthy();
    expect(screen.queryByRole('radio', { name: 'Pension · FZ' })).toBeNull();
    expect(screen.queryByRole('radio', { name: 'DKB · Old' })).toBeNull();
  });
});

describe('S7 step 2: review (#492)', () => {
  it('shows the server counts, the format, the period and each row with type and duplicate hint', async () => {
    reviewFixture({
      statementBalance: {
        outcome: 'FOUND',
        date: '2026-10-31',
        balance: '1200.00',
        currency: 'EUR',
        openingDate: '2026-10-01',
        openingBalance: '900.00',
        snapshot: 'NEW',
        existingSnapshot: null,
        adds: 'BALANCE',
        conflicts: [],
        explanation: null,
      },
    });
    await mount({ initialBatchId: 'b-1' });

    expect(await commitButton(41)).toBeTruthy();
    expect(screen.getAllByText('New 41').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Possible duplicates 5').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Errors · not imported 2').length).toBeGreaterThan(0);
    expect(await screen.findByText('DKB · DKB account · Read as DKB Girokonto (CSV)')).toBeTruthy();
    expect(screen.getByText(/synthetic-dkb\.csv · 1 Oct 2026 – 31 Oct 2026/)).toBeTruthy();
    expect(await screen.findByText('8 Nov 2026 · Expense · New')).toBeTruthy();
    expect(
      screen.getByText('Matches the booking of 8 Nov 2026: Synthetic shop EUR -12.50'),
    ).toBeTruthy();
    expect(screen.getByText(/The date cannot be read\./)).toBeTruthy();
    // Error rows have no switch.
    expect(screen.getAllByRole('switch')).toHaveLength(2);
  });

  it('switching a duplicate on reads "Import 42 transactions" from the response, and the next switch sends the new version', async () => {
    reviewFixture();
    await mount({ initialBatchId: 'b-1' });
    await commitButton(41);

    await fireEvent(
      await screen.findByRole('switch', { name: 'Import Synthetic shop 2, EUR -12.50' }),
      'valueChange',
      true,
    );
    expect(await commitButton(42)).toBeTruthy();
    await waitFor(() =>
      expect(
        screen.getByRole('switch', { name: 'Import Synthetic shop 2, EUR -12.50' }),
      ).toBeOnTheScreen(),
    );
    await fireEvent(
      screen.getByRole('switch', { name: 'Import Synthetic shop 1, EUR -12.50' }),
      'valueChange',
      false,
    );
    expect(await commitButton(41)).toBeTruthy();

    const patches = server.callsOf(
      'patch',
      '/api/v1/accounts/{accountId}/imports/{batchId}/rows/{rowNumber}',
    );
    expect(patches.map((call) => call.options)).toEqual([
      expect.objectContaining({ version: 4, params: expect.objectContaining({ rowNumber: 2 }) }),
      expect.objectContaining({ version: 5, params: expect.objectContaining({ rowNumber: 1 }) }),
    ]);
    // The answer is the batch: no extra read of it after a switch.
    expect(server.callsOf('get', '/api/v1/accounts/{accountId}/imports/{batchId}')).toHaveLength(1);
  });

  it('masks the amount in the switch label when amounts are hidden', async () => {
    mockHidden = true;
    reviewFixture();
    await mount({ initialBatchId: 'b-1' });

    expect(
      await screen.findByRole('switch', { name: 'Import Synthetic shop 1, ••••' }),
    ).toBeTruthy();
  });

  it('keeps the loaded pages when a switch moves no row between statuses', async () => {
    const rows = Array.from({ length: 60 }, (_, i) => syntheticRow(i + 1, 'PARSED'));
    reviewFixture({ counts: { ...syntheticBatch().counts, total: 60, newRows: 60 } }, rows);
    await mount({ initialBatchId: 'b-1' });
    await fireEvent.press(await screen.findByRole('button', { name: 'Load more rows' }));
    expect(await screen.findByText('Synthetic shop 55')).toBeTruthy();

    await fireEvent(
      screen.getByRole('switch', { name: 'Import Synthetic shop 55, EUR -12.50' }),
      'valueChange',
      false,
    );
    await commitButton(40);
    await waitFor(() =>
      expect(
        server.callsOf('get', '/api/v1/accounts/{accountId}/imports/{batchId}/rows'),
      ).toHaveLength(4),
    );
    // Both pages were read again in place; the member's place in the list stays.
    expect(screen.getByText('Synthetic shop 55')).toBeTruthy();
  });

  it('restarts the row list at page 0 when a re-check moved rows between statuses', async () => {
    const rows = Array.from({ length: 60 }, (_, i) => syntheticRow(i + 1, 'PARSED'));
    reviewFixture({ counts: { ...syntheticBatch().counts, total: 60, newRows: 60 } }, rows);
    await mount({ initialBatchId: 'b-1' });
    await fireEvent.press(await screen.findByRole('button', { name: 'Load more rows' }));
    expect(await screen.findByText('Synthetic shop 55')).toBeTruthy();

    server.recheckOnNextPatch = [{ rowNumber: 3, status: 'DUPLICATE' }];
    await fireEvent(
      screen.getByRole('switch', { name: 'Import Synthetic shop 1, EUR -12.50' }),
      'valueChange',
      false,
    );
    await waitFor(() => expect(screen.queryByText('Synthetic shop 55')).toBeNull());
    expect(await screen.findByText(/Now a duplicate/)).toBeTruthy();
    const pages = server
      .callsOf('get', '/api/v1/accounts/{accountId}/imports/{batchId}/rows')
      .map((call) => (call.options.query as { page: number }).page);
    expect(pages).toEqual([0, 1, 0]);
  });

  it('reloads the batch and its rows and shows the conflict dialog on VERSION_CONFLICT', async () => {
    reviewFixture();
    await mount({ initialBatchId: 'b-1' });
    await commitButton(41);
    server.failNext(
      'patch',
      '/api/v1/accounts/{accountId}/imports/{batchId}/rows/{rowNumber}',
      problem('VERSION_CONFLICT', 412),
    );
    server.batch = {
      ...syntheticBatch(),
      version: 6,
      counts: { ...syntheticBatch().counts, included: 39 },
    };

    await fireEvent(
      await screen.findByRole('switch', { name: 'Import Synthetic shop 2, EUR -12.50' }),
      'valueChange',
      true,
    );
    expect(await screen.findByText('conflict')).toBeTruthy();
    expect(await commitButton(39)).toBeTruthy();
    expect(server.callsOf('get', '/api/v1/accounts/{accountId}/imports/{batchId}')).toHaveLength(2);
  });

  it('keeps commit disabled until the account is confirmed with If-Match', async () => {
    reviewFixture({
      accountIdentification: {
        outcome: 'NO_MATCH',
        confirmationRequired: true,
        confirmed: false,
      } as Batch['accountIdentification'],
    });
    await mount({ initialBatchId: 'b-1' });

    expect(await commitButton(41)).toBeDisabled();
    await fireEvent.press(screen.getByRole('button', { name: 'Yes, this is my account' }));

    await waitFor(async () => expect(await commitButton(41)).toBeEnabled());
    const [confirm] = server.callsOf(
      'post',
      '/api/v1/accounts/{accountId}/imports/{batchId}/confirm-account',
    );
    expect(confirm.options).toMatchObject({ version: 4 });
  });

  it('names the file of a conflicting statement balance', async () => {
    reviewFixture({
      statementBalance: {
        outcome: 'FOUND',
        date: '2026-10-31',
        balance: '1200.00',
        currency: 'EUR',
        openingDate: null,
        openingBalance: null,
        snapshot: 'CONFLICTING',
        existingSnapshot: { importFileName: 'october.pdf' } as never,
        adds: 'BALANCE',
        conflicts: [],
        explanation: null,
      },
    });
    await mount({ initialBatchId: 'b-1' });

    expect(
      await screen.findByText(/differs from the one recorded from october\.pdf\./),
    ).toBeTruthy();
  });

  it('exports the error rows as errors CSV', async () => {
    reviewFixture();
    await mount({ initialBatchId: 'b-1' });
    await fireEvent.press(await screen.findByRole('button', { name: 'Download error rows' }));

    await waitFor(() =>
      expect(shareErrorsCsv).toHaveBeenCalledWith(
        expect.stringContaining('IMPORT_ROW_DATE_INVALID'),
        'import-errors.csv',
        'Download error rows',
      ),
    );
  });

  it('keeps an identical second file at "Import 0 transactions": every row a duplicate and off', async () => {
    reviewFixture(
      {
        counts: { ...syntheticBatch().counts, newRows: 0, duplicates: 48, errors: 0, included: 0 },
        sameFileImportedIn: { batchId: 'b-0', committedAt: '2026-11-08T10:00:00Z' },
      },
      [syntheticRow(1, 'DUPLICATE'), syntheticRow(2, 'DUPLICATE')],
    );
    await mount({ initialBatchId: 'b-1' });

    expect(await screen.findByText(/This exact file was imported on/)).toBeTruthy();
    expect(await commitButton(0)).toBeDisabled();
    for (const toggle of screen.getAllByRole('switch')) expect(toggle.props.value).toBe(false);
    await fireEvent.press(await commitButton(0));
    expect(server.callsOf('post', '/api/v1/accounts/{accountId}/imports/{batchId}/commit')).toEqual(
      [],
    );
  });
});

describe('S7 step 3 and cancel (#492)', () => {
  it('commits once with If-Match, shows the result and refreshes the financial data', async () => {
    reviewFixture();
    const { onShowAccount } = await mount({ initialBatchId: 'b-1' });
    await fireEvent.press(await commitButton(41));

    expect(await screen.findByText('41 transactions imported into DKB account.')).toBeTruthy();
    expect(server.callsOf('post', '/api/v1/accounts/{accountId}/imports/{batchId}/commit')).toEqual(
      [expect.objectContaining({ options: expect.objectContaining({ version: 4 }) })],
    );
    expect(invalidateFinancialData).toHaveBeenCalledTimes(1);
    await fireEvent.press(screen.getByRole('button', { name: 'Show account' }));
    expect(onShowAccount).toHaveBeenCalledWith('a-1');
  });

  it('shows step 3 when the commit answers IMPORT_BATCH_STATE for a batch already committed', async () => {
    reviewFixture();
    await mount({ initialBatchId: 'b-1' });
    await commitButton(41);
    server.failNext(
      'post',
      '/api/v1/accounts/{accountId}/imports/{batchId}/commit',
      problem('IMPORT_BATCH_STATE', 409),
    );
    server.batch = syntheticBatch({
      status: 'COMMITTED',
      version: 5,
      counts: { ...syntheticBatch().counts, imported: 41 },
    });

    await fireEvent.press(screen.getByRole('button', { name: 'Import 41 transactions' }));

    expect(await screen.findByText('41 transactions imported into DKB account.')).toBeTruthy();
    expect(screen.queryByText('This import has changed. Reload its status.')).toBeNull();
    expect(invalidateFinancialData).toHaveBeenCalledTimes(1);
  });

  it('asks before cancel and discards the uncommitted batch with its version', async () => {
    reviewFixture();
    const { onExit } = await mount({ initialBatchId: 'b-1' });
    await commitButton(41);
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(
      server.callsOf('post', '/api/v1/accounts/{accountId}/imports/{batchId}/discard'),
    ).toEqual([]);

    await fireEvent.press(screen.getByRole('button', { name: 'Discard' }));

    await waitFor(() => expect(onExit).toHaveBeenCalledTimes(1));
    expect(
      server.callsOf('post', '/api/v1/accounts/{accountId}/imports/{batchId}/discard')[0].options,
    ).toMatchObject({ version: 4 });
  });
});

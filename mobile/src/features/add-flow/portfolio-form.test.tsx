import { act, fireEvent, screen } from '@testing-library/react-native';

import { useOnline } from '@/hooks/use-online';
import type { AppLocale } from '@/i18n';
import { renderWithIntl } from '@/i18n/test-utils';

import type { Institution, Portfolio } from './model';
import { PortfolioForm, SEARCH_DEBOUNCE_MS } from './portfolio-form';
import { dkb, problem } from './test-fixtures';
import { useCreatePortfolio, useInstitutionCatalogue } from './use-create';

jest.mock('@/global.css', () => ({}));
jest.mock('expo-image', () => ({ Image: 'Image' }));
jest.mock('expo-symbols', () => ({ SymbolView: 'SymbolView' }));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => jest.requireActual('./test-fixtures').theme,
}));
jest.mock('./use-create', () => ({
  useCreatePortfolio: jest.fn(),
  useInstitutionCatalogue: jest.fn(),
}));
jest.mock('@/hooks/use-online', () => ({ useOnline: jest.fn(() => true) }));

const postFinance: Institution = {
  id: 'pf-id',
  name: 'PostFinance',
  country: 'CH',
  institutionType: 'NEOBANK', // a type this app version doesn't know (#452)
  identifier: null,
  logoUrl: null,
  importFormats: ['CAMT'],
};

let create: {
  mutateAsync: jest.Mock;
  isPending: boolean;
  isError: boolean;
  error: unknown;
  reset: jest.Mock;
};
function catalogue(rows: Institution[], extra: Record<string, unknown> = {}) {
  return {
    data: {
      pages: [
        {
          content: rows,
          page: { number: 0, size: 50, totalElements: rows.length, totalPages: 1 },
        },
      ],
    },
    isPending: false,
    isError: false,
    isSuccess: true,
    error: null,
    refetch: jest.fn(),
    hasNextPage: false,
    isFetchingNextPage: false,
    fetchNextPage: jest.fn(),
    ...extra,
  } as never;
}

async function form(locale: AppLocale = 'en') {
  const onCreated = jest.fn();
  const onCancel = jest.fn();
  await renderWithIntl(
    <PortfolioForm workspaceCurrency="CHF" onCreated={onCreated} onCancel={onCancel} />,
    locale,
  );
  return { onCreated, onCancel };
}
const createButton = () => screen.getByRole('button', { name: 'Create portfolio' });

beforeEach(() => {
  jest.clearAllMocks();
  create = {
    mutateAsync: jest.fn(async () => ({ id: 'new-portfolio' }) as Portfolio),
    isPending: false,
    isError: false,
    error: null,
    reset: jest.fn(),
  };
  jest.mocked(useOnline).mockReturnValue(true);
  jest.mocked(useCreatePortfolio).mockImplementation(() => create as never);
  jest.mocked(useInstitutionCatalogue).mockReturnValue(catalogue([dkb, postFinance]));
});

describe('Add portfolio S5 (#491)', () => {
  it('creates a DKB portfolio with the selected catalogue ID and then opens S6', async () => {
    const { onCreated } = await form();
    expect(screen.getByRole('header', { name: 'Where is it held?' })).toBeTruthy();
    await fireEvent.press(screen.getByRole('radio', { name: 'DKB' }));
    expect(screen.getByRole('radio', { name: 'DKB' })).toBeChecked();
    expect(screen.getByLabelText('Portfolio name').props.value).toBe('DKB');
    expect(screen.getByLabelText('New tab: DKB')).toBeTruthy();
    await fireEvent.press(createButton());
    expect(create.mutateAsync).toHaveBeenCalledWith({
      catalogueInstitutionId: 'dkb-id',
      name: 'DKB',
      portfolioCurrency: 'EUR',
    });
    expect(onCreated).toHaveBeenCalledWith({ id: 'new-portfolio' });
  });

  it('leaves a cleared name to the server default for a catalogue entry', async () => {
    await form();
    await fireEvent.press(screen.getByRole('radio', { name: 'DKB' }));
    await fireEvent.changeText(screen.getByLabelText('Portfolio name'), '  ');
    expect(screen.getByLabelText('New tab: DKB')).toBeTruthy();
    await fireEvent.press(createButton());
    expect(create.mutateAsync).toHaveBeenCalledWith({
      catalogueInstitutionId: 'dkb-id',
      portfolioCurrency: 'EUR',
    });
  });

  it('rejects a name over 100 characters before sending', async () => {
    await form();
    await fireEvent.press(screen.getByRole('radio', { name: 'DKB' }));
    await fireEvent.changeText(screen.getByLabelText('Portfolio name'), 'x'.repeat(101));
    expect(screen.getByText('Use a name of 1–100 characters.')).toBeTruthy();
    expect(createButton()).toBeDisabled();
  });

  it('shows type and formats, with an unknown type as "Other", grouped by country name', async () => {
    await form();
    expect(screen.getByText('Bank · CSV · PDF')).toBeTruthy();
    expect(screen.getByText('Other · CAMT')).toBeTruthy();
    expect(screen.getByRole('header', { name: 'Germany' })).toBeTruthy();
    expect(screen.getByRole('header', { name: 'Switzerland' })).toBeTruthy();
  });

  it('disables custom creation until a name is entered', async () => {
    await form();
    await fireEvent.press(screen.getByRole('radio', { name: /Not in the list/ }));
    expect(createButton()).toBeDisabled();
    await fireEvent.press(createButton());
    expect(create.mutateAsync).not.toHaveBeenCalled();
    await fireEvent.changeText(screen.getByLabelText('Portfolio name'), 'My bank');
    await fireEvent.press(createButton());
    expect(create.mutateAsync).toHaveBeenCalledWith({
      name: 'My bank',
      country: 'CH',
      institutionType: 'BANK',
      portfolioCurrency: 'CHF',
    });
  });

  it('keeps "Create your own" in the institution radio group', async () => {
    await form();
    const group = screen.getByLabelText('Choose an institution');
    expect(group.props.accessibilityRole).toBe('radiogroup');
    expect(screen.getByRole('radio', { name: /Not in the list/ })).toBeTruthy();
    expect(JSON.stringify(screen.toJSON())).not.toContain('"accessibilityRole":"tab"');
  });

  it('picks any ISO country and currency for your own portfolio', async () => {
    await form();
    await fireEvent.press(screen.getByRole('radio', { name: /Not in the list/ }));
    await fireEvent.changeText(screen.getByLabelText('Portfolio name'), 'Nordea');
    await fireEvent.press(screen.getByRole('button', { name: 'Country: Switzerland' }));
    await fireEvent.changeText(screen.getByLabelText('Search'), 'swed');
    await fireEvent.press(screen.getByRole('radio', { name: 'Sweden' }));
    await fireEvent.press(screen.getByRole('button', { name: /^Portfolio currency/ }));
    await fireEvent.changeText(screen.getByLabelText('Search'), 'SEK');
    await fireEvent.press(screen.getByRole('radio', { name: 'SEK · Swedish Krona' }));
    await fireEvent.press(createButton());
    expect(create.mutateAsync).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'Nordea', country: 'SE', portfolioCurrency: 'SEK' }),
    );
  });

  it('debounces search and filters by one country, "All" for none', async () => {
    jest.useFakeTimers();
    try {
      await form();
      await fireEvent.changeText(
        screen.getByLabelText('Search banks, brokers and 3a providers'),
        'post',
      );
      await act(async () => {
        jest.advanceTimersByTime(SEARCH_DEBOUNCE_MS - 1);
      });
      expect(useInstitutionCatalogue).toHaveBeenLastCalledWith('', null);
      await act(async () => {
        jest.advanceTimersByTime(1);
      });
      expect(useInstitutionCatalogue).toHaveBeenLastCalledWith('post', null);
      expect(screen.getByRole('radio', { name: 'All' })).toBeChecked();
      await fireEvent.press(screen.getByRole('radio', { name: 'Germany' }));
      expect(useInstitutionCatalogue).toHaveBeenLastCalledWith('post', 'DE');
      await fireEvent.press(screen.getByRole('radio', { name: 'All' }));
      expect(useInstitutionCatalogue).toHaveBeenLastCalledWith('post', null);
    } finally {
      jest.useRealTimers();
    }
  });

  it('offers "Create your own" with the search as name when nothing matches', async () => {
    jest.useFakeTimers();
    try {
      jest.mocked(useInstitutionCatalogue).mockReturnValue(catalogue([]));
      await form();
      await fireEvent.changeText(
        screen.getByLabelText('Search banks, brokers and 3a providers'),
        'Hypo Bank',
      );
      await act(async () => {
        jest.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
      });
      expect(screen.getByText(/No match for “Hypo Bank”/)).toBeTruthy();
      await fireEvent.press(screen.getByRole('radio', { name: /Not in the list/ }));
      expect(screen.getByLabelText('Portfolio name').props.value).toBe('Hypo Bank');
    } finally {
      jest.useRealTimers();
    }
  });

  it('sends one POST for a double tap', async () => {
    create.mutateAsync.mockReturnValue(new Promise(() => undefined));
    await form();
    await fireEvent.press(screen.getByRole('radio', { name: 'DKB' }));
    await fireEvent.press(createButton());
    await fireEvent.press(createButton());
    expect(create.mutateAsync).toHaveBeenCalledTimes(1);
  });

  it('marks the fields a VALIDATION_FAILED names', async () => {
    create.isError = true;
    create.error = problem(400, 'VALIDATION_FAILED', {
      errors: [{ field: 'portfolioCurrency', message: 'raw' }],
    });
    await form();
    await fireEvent.press(screen.getByRole('radio', { name: 'DKB' }));
    expect(screen.getAllByText('Please check this field.')).toHaveLength(1);
    expect(screen.queryByText('raw')).toBeNull();
  });

  it('stays usable in German and disables server writes while offline', async () => {
    jest.mocked(useOnline).mockReturnValue(false);
    await form('de');
    await fireEvent.press(screen.getByRole('radio', { name: 'DKB' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Portfolio erstellen' }));
    expect(create.mutateAsync).not.toHaveBeenCalled();
    expect(screen.getByText(/Du bist offline/)).toBeTruthy();
    expect(screen.getByRole('header', { name: 'Deutschland' })).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'Alle' })).toBeChecked();
  });

  it('allows a custom portfolio even if the catalogue fails', async () => {
    jest
      .mocked(useInstitutionCatalogue)
      .mockReturnValue(
        catalogue([], { isError: true, isSuccess: false, error: new Error('offline catalogue') }),
      );
    await form();
    await fireEvent.press(screen.getByRole('radio', { name: /Create your own/ }));
    await fireEvent.changeText(screen.getByLabelText('Portfolio name'), 'Independent');
    await fireEvent.press(createButton());
    expect(create.mutateAsync).toHaveBeenCalledTimes(1);
  });
});

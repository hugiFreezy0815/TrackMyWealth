import {
  type AccountProposal,
  prepareAccountDraft,
  proposeAccountFromBatch,
  registerIdentifierFromImport,
} from './account-proposal';

function mockFetchOnce(body: unknown) {
  const fetchMock = jest.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body });
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  return fetchMock;
}

const proposal: AccountProposal = {
  templateId: 'template',
  institutionCatalogueId: 'catalogue',
  institutionName: 'Synthetic Bank',
  suggestedAccountType: 'CASH',
  suggestedCurrency: 'CHF',
  maskedIdentifier: '••••3000',
};

describe('account proposal confirmation', () => {
  afterEach(() => jest.resetAllMocks());

  it('needs one of the workspace institutions and a name', () => {
    expect(() => prepareAccountDraft('', 'Checking', 'CASH', 'CHF')).toThrow();
    expect(() => prepareAccountDraft('institution', ' ', 'CASH', 'CHF')).toThrow();
  });

  it('accepts only the account types the backend knows and an ISO currency', () => {
    expect(() => prepareAccountDraft('institution', 'Checking', 'CHECKING', 'CHF')).toThrow();
    expect(() => prepareAccountDraft('institution', 'Checking', 'CASH', 'CH')).toThrow();
  });

  it('builds the create-account request from the confirmed values only', () => {
    expect(prepareAccountDraft('institution', ' Checking ', 'CASH', 'chf')).toEqual({
      financialInstitutionId: 'institution',
      name: 'Checking',
      accountType: 'CASH',
      nativeCurrency: 'CHF',
    });
  });

  it('asks for the proposal of a batch whose file names no account', async () => {
    const fetchMock = mockFetchOnce(proposal);

    await expect(proposeAccountFromBatch('account', 'batch')).resolves.toEqual(proposal);
    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/v1/accounts/account/imports/batch/account-proposal',
      expect.objectContaining({ method: 'POST' }),
    );
  });

  it('registers the identifier from the file without sending it', async () => {
    const fetchMock = mockFetchOnce({
      accountId: 'new',
      kind: 'IBAN',
      identifierMasked: '••••3000',
      version: 2,
    });

    await registerIdentifierFromImport('new', 1, 'account', 'batch');

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('http://localhost:8080/api/v1/accounts/new/identifier/from-import');
    expect(init).toEqual(
      expect.objectContaining({
        method: 'PUT',
        headers: expect.objectContaining({ 'If-Match': '"1"' }),
        body: JSON.stringify({ importAccountId: 'account', batchId: 'batch' }),
      }),
    );
  });
});

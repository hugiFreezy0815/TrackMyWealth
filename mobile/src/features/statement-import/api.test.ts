import {
  NetworkError,
  UPLOAD_TIMEOUT_MS,
  REQUEST_TIMEOUT_MS,
  setAccessToken,
  setRefreshHandler,
} from '@/api/client';
import { typedApi } from '@/api/typed-api';

import {
  commitStatement,
  confirmStatementAccount,
  discardStatement,
  downloadErrorsCsv,
  parseStatement,
  setStatementRowIncluded,
  statementForm,
  uploadStatement,
} from './api';
import type { Batch } from './model';
import { File } from 'expo-file-system';
// Expo's internal encoder, on purpose: it is the code that rejected the old part on the phone, and
// no public export reaches it. If an Expo upgrade moves it, move this import with it.
import { convertFormDataAsync } from 'expo/src/winter/fetch/convertFormData';

// The JSON calls are checked for their options; upload and text run the real transport on fetch.
jest.mock('expo-file-system', () => ({
  File: jest.fn().mockImplementation(() => ({
    bytes: async () => new TextEncoder().encode('Date;Amount\n'),
  })),
}));
jest.mock('@/api/typed-api', () => ({
  typedApi: {
    ...jest.requireActual('@/api/typed-api').typedApi,
    get: jest.fn(),
    post: jest.fn(),
    patch: jest.fn(),
  },
}));
const batch = { id: 'b-1', accountId: 'a-1', version: 7 } as Batch;

beforeEach(() => {
  jest.clearAllMocks();
  setAccessToken(null);
  setRefreshHandler(null);
});

describe('versioned import requests (#492)', () => {
  it('sends each row inclusion PATCH with the latest server version', async () => {
    await setStatementRowIncluded('a-1', batch, 23, true);
    expect(typedApi.patch).toHaveBeenCalledWith(
      '/api/v1/accounts/{accountId}/imports/{batchId}/rows/{rowNumber}',
      {
        params: { accountId: 'a-1', batchId: 'b-1', rowNumber: 23 },
        version: 7,
        body: { included: true },
      },
    );
  });
  it('sends selected template and If-Match for parse', async () => {
    await parseStatement('a-1', batch, 'template-dkb');
    expect(typedApi.post).toHaveBeenCalledWith(
      '/api/v1/accounts/{accountId}/imports/{batchId}/parse',
      {
        params: { accountId: 'a-1', batchId: 'b-1' },
        version: 7,
        body: { templateId: 'template-dkb' },
      },
    );
  });
  it('requires version for confirm, commit and discard', async () => {
    await confirmStatementAccount('a-1', batch);
    await commitStatement('a-1', batch);
    await discardStatement('a-1', batch);
    expect(typedApi.post).toHaveBeenCalledTimes(3);
    for (const [, options] of jest.mocked(typedApi.post).mock.calls) {
      expect(options).toEqual(expect.objectContaining({ version: 7 }));
    }
  });
});

const file = { name: 'synthetic-dkb.csv', uri: 'file:///dkb.csv', size: 100, mimeType: 'text/csv' };

/** A fetch that never answers until its signal aborts. */
function hangingFetch() {
  return jest.fn(
    (_url: string, init: RequestInit) =>
      new Promise((_resolve, reject) =>
        init.signal?.addEventListener('abort', () =>
          reject(Object.assign(new Error('Aborted'), { name: 'AbortError' })),
        ),
      ),
  ) as unknown as typeof fetch;
}

describe('native statement part (#636)', () => {
  // On iOS and Android the global FormData is React Native's, patched by Expo (SDK 58): the same
  // pairing as on the phone, so the part goes through the encoder Expo's fetch uses.
  const jestFormData = globalThis.FormData;
  beforeAll(() => {
    // React Native's own FormData (a Flow module without type declarations for this path).
    const ReactNativeFormData = jest.requireActual<{ default: typeof FormData }>(
      'react-native/Libraries/Network/FormData',
    ).default;
    // Patches the class in place, as Expo's runtime does on the phone (jest-expo stubs the runtime).
    jest
      .requireActual<typeof import('expo/src/winter/FormData')>('expo/src/winter/FormData')
      .installFormDataPatch(ReactNativeFormData);
    globalThis.FormData = ReactNativeFormData;
  });
  afterAll(() => {
    globalThis.FormData = jestFormData;
  });

  type Part = [string, { name?: string; type?: string; uri?: string; bytes?: () => unknown }];
  const partsOf = (form: FormData) => (form as unknown as { _parts: Part[] })._parts;

  it('sends the picked copy as bytes with the statement name and type, never as a uri', async () => {
    const [[field, part]] = partsOf(statementForm(file));

    expect(field).toBe('file');
    expect(part).toMatchObject({ name: 'synthetic-dkb.csv', type: 'text/csv' });
    expect(part).not.toHaveProperty('uri');
    await expect(part.bytes?.()).resolves.toEqual(new TextEncoder().encode('Date;Amount\n'));
    expect(File).toHaveBeenCalledWith('file:///dkb.csv');
  });

  it('falls back to a generic media type when the picker reports none', () => {
    const [[, part]] = partsOf(statementForm({ ...file, mimeType: undefined }));
    expect(part.type).toBe('application/octet-stream');
  });

  // Expo's native fetch encodes the body with this converter; React Native's { uri } part made it
  // throw "Unsupported FormDataPart implementation" before anything was sent.
  it("is accepted by Expo's native multipart encoder", async () => {
    const { body, boundary } = await convertFormDataAsync(statementForm(file));
    const text = new TextDecoder().decode(body);

    expect(text).toContain(
      'content-disposition: form-data; name="file"; filename="synthetic-dkb.csv"',
    );
    expect(text).toContain('content-type: text/csv');
    expect(text).toContain('Date;Amount');
    expect(text.trimEnd()).toMatch(new RegExp(`--${boundary}--$`));
  });
});

describe('web statement part (#636)', () => {
  it("appends the browser File as it is, with the statement's name", () => {
    jest.mocked(File).mockClear();
    const webFile = new globalThis.File(['Date;Amount\n'], 'synthetic-dkb.csv', {
      type: 'text/csv',
    });

    const form = statementForm({ ...file, webFile });
    const sent = form.get('file');

    expect(form.getAll('file')).toHaveLength(1);
    expect(sent).toBeInstanceOf(globalThis.File);
    expect((sent as globalThis.File).name).toBe('synthetic-dkb.csv');
    expect((sent as globalThis.File).type).toBe('text/csv');
    expect(File).not.toHaveBeenCalled();
  });
});

describe('authenticated multipart and CSV transport (#492)', () => {
  it('uploads to the contract path with the bearer token but no JSON Content-Type', async () => {
    const mock = jest.fn(async () => new Response(JSON.stringify({ id: 'b-1' }), { status: 201 }));
    globalThis.fetch = mock as unknown as typeof fetch;
    setAccessToken('token-123');

    await expect(uploadStatement('a 1', file, undefined, 'upload-uuid')).resolves.toEqual({
      id: 'b-1',
    });

    const [url, init] = mock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toMatch(/\/api\/v1\/accounts\/a%201\/imports$/);
    expect(init.method).toBe('POST');
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer token-123');
    expect((init.headers as Record<string, string>)['Idempotency-Key']).toBe('upload-uuid');
    expect((init.headers as Record<string, string>)['Content-Type']).toBeUndefined();
  });

  it('renews the session once on a 401 and sends the upload again with the new token', async () => {
    const mock = jest
      .fn()
      .mockResolvedValueOnce(new Response('{}', { status: 401 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: 'b-1' }), { status: 201 }));
    globalThis.fetch = mock as unknown as typeof fetch;
    setAccessToken('old');
    setRefreshHandler(async () => {
      setAccessToken('new');
      return 'new';
    });

    await uploadStatement('a-1', file, undefined, 'same-uuid');

    expect(mock).toHaveBeenCalledTimes(2);
    const [, original] = mock.mock.calls[0] as [string, RequestInit];
    const [, retry] = mock.mock.calls[1] as [string, RequestInit];
    expect((original.headers as Record<string, string>)['Idempotency-Key']).toBe('same-uuid');
    expect((retry.headers as Record<string, string>)['Idempotency-Key']).toBe('same-uuid');
    expect((retry.headers as Record<string, string>).Authorization).toBe('Bearer new');
    expect(retry.body).toBeInstanceOf(FormData);
  });

  it('turns a dropped 201 response body into an uncertain NetworkError', async () => {
    const broken = new Response(
      new ReadableStream<Uint8Array>({
        start(controller) {
          controller.error(new Error('Response stream disconnected'));
        },
      }),
      { status: 201 },
    );
    const fetchMock = jest.fn(async () => broken);
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    await expect(uploadStatement('a-1', file, undefined, 'body-lost-uuid')).rejects.toBeInstanceOf(
      NetworkError,
    );
    const [, request] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect((request.headers as Record<string, string>)['Idempotency-Key']).toBe('body-lost-uuid');
  });

  it('keeps cancellation active after headers, while reading the multipart response', async () => {
    const cancel = new AbortController();
    let bodyStarted: () => void = () => {
      throw new Error('Body reader not set up');
    };
    const reading = new Promise<void>((resolve) => {
      bodyStarted = resolve;
    });
    let fetchSignal: AbortSignal | null = null;
    const response = new Response('{}', { status: 201 });
    response.json = () =>
      new Promise<never>((_resolve, reject) => {
        if (!fetchSignal) throw new Error('Fetch did not provide an abort signal');
        const signal = fetchSignal;
        const cancelled = Object.assign(new Error('Cancelled'), { name: 'AbortError' });
        signal.addEventListener('abort', () => reject(cancelled), { once: true });
        bodyStarted();
      });
    globalThis.fetch = jest.fn(async (_url: string, init: RequestInit) => {
      fetchSignal = init.signal ?? null;
      return response;
    }) as unknown as typeof fetch;

    const pending = uploadStatement('a-1', file, cancel.signal);
    await reading;
    cancel.abort();
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    const receivedSignal = () => fetchSignal;
    expect(receivedSignal()?.aborted).toBe(true);
  });

  it('keeps the upload deadline active after 201 headers have arrived', async () => {
    jest.useFakeTimers();
    try {
      let fetchSignal: AbortSignal | null = null;
      let bodyStarted: () => void = () => {
        throw new Error('Body reader not set up');
      };
      const reading = new Promise<void>((resolve) => {
        bodyStarted = resolve;
      });
      globalThis.fetch = jest.fn(async (_url: string, init: RequestInit) => {
        fetchSignal = init.signal ?? null;
        const response = new Response('{}', { status: 201 });
        response.json = () =>
          new Promise<never>((_resolve, reject) => {
            if (!fetchSignal) throw new Error('Fetch did not provide an abort signal');
            const cancelled = Object.assign(new Error('Aborted'), { name: 'AbortError' });
            fetchSignal.addEventListener('abort', () => reject(cancelled), { once: true });
            bodyStarted();
          });
        return response;
      }) as unknown as typeof fetch;

      const pending = uploadStatement('a-1', file, undefined, 'deadline-uuid');
      // Observe the rejection before the fake timer fires, so Jest cannot treat it as unhandled.
      const outcome = pending.catch((error: unknown) => error);
      await reading;
      await jest.advanceTimersByTimeAsync(UPLOAD_TIMEOUT_MS + 1);
      const failure = await outcome;
      expect(failure).toBeInstanceOf(NetworkError);
      expect(failure).toMatchObject({ message: expect.stringMatching(/timed out/) });
      const receivedSignal = () => fetchSignal;
      expect(receivedSignal()?.aborted).toBe(true);
    } finally {
      jest.useRealTimers();
    }
  });

  it('gives an upload the upload deadline, not the JSON one', async () => {
    jest.useFakeTimers();
    try {
      globalThis.fetch = hangingFetch();
      const pending = uploadStatement('a-1', file);
      const outcome = jest.fn();
      pending.then(outcome, outcome);

      await jest.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS + 1);
      expect(outcome).not.toHaveBeenCalled();

      await jest.advanceTimersByTimeAsync(UPLOAD_TIMEOUT_MS);
      await expect(pending).rejects.toBeInstanceOf(NetworkError);
      await expect(pending).rejects.toThrow(/timed out/);
    } finally {
      jest.useRealTimers();
    }
  });

  it("keeps the member's Cancel an abort, not a network error", async () => {
    globalThis.fetch = hangingFetch();
    const cancel = new AbortController();
    const pending = uploadStatement('a-1', file, cancel.signal);

    cancel.abort();

    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('downloads the errors CSV as text from the contract path', async () => {
    const mock = jest.fn(
      async () =>
        new Response('row,error\n1,invalid', {
          status: 200,
          headers: { 'Content-Type': 'text/csv' },
        }),
    );
    globalThis.fetch = mock as unknown as typeof fetch;

    expect(await downloadErrorsCsv('a-1', 'b-1')).toBe('row,error\n1,invalid');
    const [url, init] = mock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toMatch(/\/api\/v1\/accounts\/a-1\/imports\/b-1\/errors\.csv$/);
    expect(init.method).toBe('GET');
  });

  it('keeps the problem code and Retry-After of a busy parser', async () => {
    globalThis.fetch = jest.fn(
      async () =>
        new Response(JSON.stringify({ status: 503, title: 'busy', code: 'IMPORT_PDF_BUSY' }), {
          status: 503,
          headers: { 'Retry-After': '3' },
        }),
    ) as unknown as typeof fetch;

    await expect(uploadStatement('a-1', file)).rejects.toMatchObject({
      status: 503,
      retryAfterSeconds: 3,
      body: { code: 'IMPORT_PDF_BUSY' },
    });
  });
});

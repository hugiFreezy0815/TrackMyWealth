import { shareErrorsCsv } from './device-files.web';

describe('error CSV download on the web (#492)', () => {
  const realDocument = globalThis.document;
  const realCreate = URL.createObjectURL;
  const realRevoke = URL.revokeObjectURL;

  afterEach(() => {
    globalThis.document = realDocument;
    URL.createObjectURL = realCreate;
    URL.revokeObjectURL = realRevoke;
    jest.useRealTimers();
  });

  it('downloads a CSV file and revokes its URL only after the click', async () => {
    jest.useFakeTimers();
    const anchor = { href: '', download: '', style: {}, click: jest.fn(), remove: jest.fn() };
    const appendChild = jest.fn();
    globalThis.document = {
      createElement: jest.fn(() => anchor),
      body: { appendChild },
    } as unknown as Document;
    URL.createObjectURL = jest.fn(() => 'blob:errors');
    URL.revokeObjectURL = jest.fn();

    await shareErrorsCsv('row,error', 'import-errors.csv', 'unused');

    expect(anchor).toMatchObject({ href: 'blob:errors', download: 'import-errors.csv' });
    expect(appendChild).toHaveBeenCalledWith(anchor);
    expect(anchor.click).toHaveBeenCalledTimes(1);
    expect(anchor.remove).toHaveBeenCalledTimes(1);
    expect(URL.revokeObjectURL).not.toHaveBeenCalled();
    jest.runAllTimers();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:errors');
  });
});

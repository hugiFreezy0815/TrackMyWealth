import { File } from 'expo-file-system';
import * as Sharing from 'expo-sharing';

import { deletePickedCopy, shareErrorsCsv } from './device-files';

const mockFiles = new Map<string, { exists: boolean; content?: string }>();

jest.mock('expo-file-system', () => ({
  Paths: { cache: { uri: 'file:///cache/' } },
  File: jest.fn().mockImplementation((base: string | { uri: string }, name?: string) => {
    const uri = typeof base === 'string' ? base : `${base.uri}${name}`;
    const state = mockFiles.get(uri) ?? { exists: false };
    mockFiles.set(uri, state);
    return {
      uri,
      get exists() {
        return state.exists;
      },
      create: jest.fn(() => {
        state.exists = true;
      }),
      write: jest.fn(async (content: string) => {
        state.content = content;
      }),
      delete: jest.fn(() => {
        if (!state.exists) throw new Error('missing');
        state.exists = false;
      }),
    };
  }),
}));
jest.mock('expo-sharing', () => ({ shareAsync: jest.fn(async () => {}) }));

beforeEach(() => {
  mockFiles.clear();
  jest.clearAllMocks();
});

describe('statement files on the device (#492)', () => {
  it('deletes the picker copy of an uploaded statement', () => {
    mockFiles.set('file:///cache/DocumentPicker/dkb.csv', { exists: true });

    deletePickedCopy('file:///cache/DocumentPicker/dkb.csv');

    expect(mockFiles.get('file:///cache/DocumentPicker/dkb.csv')?.exists).toBe(false);
  });

  it('never fails the import when the copy is already gone or cannot be read', () => {
    expect(() => deletePickedCopy('file:///cache/gone.csv')).not.toThrow();
    jest.mocked(File).mockImplementationOnce(() => {
      throw new Error('bad uri');
    });
    expect(() => deletePickedCopy('content://bad')).not.toThrow();
  });

  it('shares the error rows as a CSV attachment and removes it afterwards', async () => {
    await shareErrorsCsv('row,error\n7,bad', 'import-errors.csv', 'Download error rows');

    expect(Sharing.shareAsync).toHaveBeenCalledWith('file:///cache/import-errors.csv', {
      mimeType: 'text/csv',
      UTI: 'public.comma-separated-values-text',
      dialogTitle: 'Download error rows',
    });
    expect(mockFiles.get('file:///cache/import-errors.csv')).toEqual({
      exists: false,
      content: 'row,error\n7,bad',
    });
  });

  it('removes the attachment even when sharing fails', async () => {
    jest.mocked(Sharing.shareAsync).mockRejectedValueOnce(new Error('no share target'));

    await expect(shareErrorsCsv('x', 'import-errors.csv', 'title')).rejects.toThrow();
    expect(mockFiles.get('file:///cache/import-errors.csv')?.exists).toBe(false);
  });
});

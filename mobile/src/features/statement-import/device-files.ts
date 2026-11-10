import { File, Paths } from 'expo-file-system';
import * as Sharing from 'expo-sharing';

/**
 * The document picker copies the statement into the app's cache (`copyToCacheDirectory`), which
 * the upload reads. Once the server has it, the copy goes: a bank statement must not stay on the
 * device (NFR-SEC, #492 review). A file that is already gone is fine.
 */
export function deletePickedCopy(uri: string): void {
  try {
    const copy = new File(uri);
    if (copy.exists) copy.delete();
  } catch {
    // Best effort: the OS clears the cache directory too; the import itself is unaffected.
  }
}

/**
 * S7 "Download error rows", phone: the share sheet with an `errors.csv` attachment. The file is
 * written to the cache for the share sheet only and deleted once it has closed.
 */
export async function shareErrorsCsv(csv: string, fileName: string, title: string): Promise<void> {
  const file = new File(Paths.cache, fileName);
  file.create({ overwrite: true });
  await file.write(csv);
  try {
    await Sharing.shareAsync(file.uri, {
      mimeType: 'text/csv',
      UTI: 'public.comma-separated-values-text',
      dialogTitle: title,
    });
  } finally {
    if (file.exists) file.delete();
  }
}

/** The browser hands the upload a File object; nothing is copied to delete. */
export function deletePickedCopy(_uri: string): void {}

/** S7 "Download error rows", web: a file download of the server's CSV. */
export async function shareErrorsCsv(csv: string, fileName: string, _title: string): Promise<void> {
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  anchor.style.display = 'none';
  // Firefox only follows a link that is in the document.
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  // Revoking at once can cancel the download in Safari and Firefox; the browser has the blob by
  // the next task.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

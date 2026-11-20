/** Read successive pages when a screen needs the complete collection. */
export async function readAllPages<T>(
  read: (page: number) => Promise<{ content: T[]; page: { totalPages: number } }>,
): Promise<T[]> {
  const rows: T[] = [];
  let page = 0;
  let totalPages: number;
  do {
    const result = await read(page++);
    rows.push(...result.content);
    totalPages = result.page.totalPages;
  } while (page < totalPages);
  return rows;
}

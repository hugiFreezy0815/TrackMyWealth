export function collectionPage<T>(
  content: T[],
  number = 0,
  size = 50,
  totalElements = content.length,
) {
  return {
    content,
    page: { number, size, totalElements, totalPages: Math.ceil(totalElements / size) },
  };
}

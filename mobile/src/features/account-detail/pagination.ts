/** Return the currently loaded tail only when one of the final ten rows is visible.
 * The caller retains the last triggered tail so a repeated viewability event cannot
 * dispatch duplicate requests for the same page.
 */
export function nextPageTrigger(
  ids: readonly string[],
  visibleIds: readonly string[],
  lastTriggeredTail: string | null,
): string | null {
  const tail = ids[ids.length - 1];
  if (!tail || tail === lastTriggeredTail) return null;
  const lastTen = new Set(ids.slice(-10));
  return visibleIds.some((id) => lastTen.has(id)) ? tail : null;
}

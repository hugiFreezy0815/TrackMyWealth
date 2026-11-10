import { PositionList, type PositionViewProps } from './position-list';

/** Native: the `PositionRow` list (M06). Web renders position-view.web.tsx (ADR 0007). */
export function PositionView(props: PositionViewProps) {
  return <PositionList {...props} />;
}

import { useIntl } from 'react-intl';
import { ShellScreen } from '@/features/shell/screen';
import { ThemedText } from '@/components/themed-text';

/** #489 owns the data cards; the shell deliberately never invents financial totals. */
export default function OverviewRoute() {
  const intl = useIntl();
  return (
    <ShellScreen title={intl.formatMessage({ id: 'nav.overview' })}>
      <ThemedText>{intl.formatMessage({ id: 'overview.pending' })}</ThemedText>
    </ShellScreen>
  );
}

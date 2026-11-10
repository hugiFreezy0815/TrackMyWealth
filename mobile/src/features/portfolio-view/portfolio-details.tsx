import { useIntl } from 'react-intl';
import { Text } from 'react-native';

import { ActionButton, KeyValueList, SectionCard } from '@/components/shared';
import { msg } from '@/features/overview/overview-text';
import { formatDateTime } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';

import type { HeaderData } from './portfolio-header';
import { typeMessageKey, type PortfolioView } from './model';

/**
 * W02 right column, top: "Portfolio details". "Mixed (suggested)" while the server's suggestion
 * was not overridden. "Edit" is not a sprint-7 screen and stays hidden.
 */
export function PortfolioDetails({ data, view }: { data: HeaderData; view: PortfolioView }) {
  const intl = useIntl();
  const typeKey = typeMessageKey(data.type);
  const type = typeKey
    ? view.portfolioTypeOverridden
      ? msg(intl, typeKey)
      : msg(intl, 'portfolio.type.suggested', { type: msg(intl, typeKey) })
    : null;
  const items = [
    { key: 'name', label: msg(intl, 'portfolio.details.name'), value: data.name },
    ...(type ? [{ key: 'type', label: msg(intl, 'portfolio.details.type'), value: type }] : []),
    ...(data.institutionName
      ? [
          {
            key: 'institution',
            label: msg(intl, 'portfolio.details.institution'),
            value: data.institutionName,
          },
        ]
      : []),
    { key: 'currency', label: msg(intl, 'portfolio.details.currency'), value: data.currency },
    {
      key: 'accounts',
      label: msg(intl, 'portfolio.details.accounts'),
      // A count of the rows the server sent, not a financial figure.
      value: String(view.sections.reduce((count, section) => count + section.accounts.length, 0)),
    },
  ];
  return (
    <SectionCard title={msg(intl, 'portfolio.details.title')}>
      <KeyValueList items={items} />
    </SectionCard>
  );
}

/**
 * Footer "Data source" (#482): the newest committed import and the Import entry point. The
 * Personal assets default portfolio has no import footer (spec S2, states).
 */
export function DataSourceCard({
  view,
  online,
  onImport,
}: {
  view: PortfolioView;
  online: boolean;
  onImport: () => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  return (
    <SectionCard title={msg(intl, 'portfolio.source.title')}>
      <Text style={{ color: theme.textSecondary }}>
        {view.lastImport
          ? msg(intl, 'portfolio.source.last', {
              format: view.lastImport.fileFormat,
              date: formatDateTime(view.lastImport.committedAt, intl.locale),
              count: view.lastImport.transactionCount,
            })
          : msg(intl, 'portfolio.source.none')}
      </Text>
      <ActionButton label={msg(intl, 'portfolio.import')} onPress={onImport} disabled={!online} />
    </SectionCard>
  );
}

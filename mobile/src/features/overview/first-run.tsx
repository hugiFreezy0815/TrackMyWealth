import type { IntlShape } from 'react-intl';
import { View } from 'react-native';

import { ActionButton, SectionCard } from '@/components/shared';
import { Spacing } from '@/constants/theme';
import { InfoText, msg } from './overview-text';

const STEPS = [
  'overview.firstRun.step1',
  'overview.firstRun.step2',
  'overview.firstRun.step3',
] as const;

/** S1 empty state (M20): no figures are estimated before the first data arrives. */
export function FirstRun({
  intl,
  offline,
  onAddPortfolio,
}: {
  intl: IntlShape;
  offline: boolean;
  onAddPortfolio: () => void;
}) {
  return (
    <View style={{ gap: Spacing.three }}>
      <SectionCard title={msg(intl, 'overview.firstRun.noFigures')}>
        <InfoText>{msg(intl, 'overview.firstRun.description')}</InfoText>
      </SectionCard>
      <SectionCard title={msg(intl, 'overview.firstRun.heading')}>
        {STEPS.map((id, index) => (
          <InfoText key={id}>
            {msg(intl, 'overview.firstRun.stepNumbered', {
              number: index + 1,
              step: msg(intl, id),
            })}
          </InfoText>
        ))}
        <ActionButton
          label={msg(intl, 'overview.firstRun.action')}
          onPress={onAddPortfolio}
          disabled={offline}
        />
      </SectionCard>
    </View>
  );
}

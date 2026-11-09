import { useState } from 'react';
import type { IntlShape } from 'react-intl';
import { Text, View } from 'react-native';

import { labelFor } from '@/api/enum-label';
import { Amount, ValueStatusBadge } from '@/components/shared';
import { amountTexts } from '@/components/shared/amount';
import { valueStatusTexts } from '@/components/shared/value-status';
import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import type { NetWorth } from './overview-model';
import { InfoText, msg } from './overview-text';

const SECTION_LABELS: Record<NetWorth['sections'][number]['section'], string> = {
  CASH_AND_SAVINGS: 'overview.sections.CASH_AND_SAVINGS',
  PILLAR_3A: 'overview.sections.PILLAR_3A',
  PENSION: 'overview.sections.PENSION',
  DEPOT: 'overview.sections.DEPOT',
  CRYPTO: 'overview.sections.CRYPTO',
  CARDS: 'overview.sections.CARDS',
  PROPERTY_AND_MORTGAGE: 'overview.sections.PROPERTY_AND_MORTGAGE',
  OTHER: 'overview.sections.OTHER',
};

/** M03: 12 px between tiles, 16 px inside, 2 per row on a phone; W01: 4 per row when they fit. */
const GAP = Spacing.compact;
const PADDING = Spacing.three;
const MIN_TILE_FOR_FOUR = 160;
/** M03 tile figure: 16 px bold; it steps down only when the figure would not fit on one line. */
const FIGURE_SIZE = 16;
const MIN_FIGURE_SIZE = 11;
/** Average advance of the figure's glyphs (semibold digits, "CHF", "'") in em; errs wide. */
const GLYPH_EM = 0.62;

export function columnsFor(width: number): 2 | 4 {
  return width >= MIN_TILE_FOR_FOUR * 4 + GAP * 3 ? 4 : 2;
}

/**
 * The largest size (at most 16 px) at which `text` fits `available` px on one line: a figure is
 * never broken mid-number (UX-02 section 3) and the M03 two-column grid stays.
 */
export function figureSize(text: string, available: number): number {
  if (available <= 0) return FIGURE_SIZE;
  const fitting = Math.floor(available / (text.length * GLYPH_EM));
  return Math.max(MIN_FIGURE_SIZE, Math.min(FIGURE_SIZE, fitting));
}

/** S1 #16 (#481), Figma M03/W01: the server's sections in server order as compact tiles. */
export function SectionTiles({
  data,
  hidden,
  intl,
}: {
  data: NetWorth;
  hidden: boolean;
  intl: IntlShape;
}) {
  const theme = useTheme();
  const [width, setWidth] = useState(0);
  if (!data.sections.length) return null;
  const columns = columnsFor(width);
  const tileWidth = width > 0 ? (width - GAP * (columns - 1)) / columns : undefined;
  return (
    <View
      testID="overview-section-tiles"
      onLayout={(event) => setWidth(event.nativeEvent.layout.width)}
      style={{ flexDirection: 'row', flexWrap: 'wrap', gap: GAP }}
    >
      {data.sections.map((section) => {
        const title = msg(
          intl,
          labelFor(SECTION_LABELS, section.section, 'overview.unknownSection'),
        );
        const valueKnown = section.valueStatus !== 'UNKNOWN';
        const texts = amountTexts(intl, {
          value: section.subtotal,
          currency: data.currency,
          masked: hidden,
          valueKnown,
        });
        const size = figureSize(texts.display, (tileWidth ?? 0) - PADDING * 2);
        const status = valueStatusTexts(section.valueStatus, null, intl)?.label;
        return (
          <View
            key={section.section}
            testID={'overview-section-' + section.section}
            style={{
              width: tileWidth ?? '47%',
              padding: PADDING,
              gap: Spacing.one,
              borderRadius: Radii.card,
              borderWidth: 1,
              borderColor: theme.borderDefault,
              backgroundColor: theme.bgSurface,
            }}
          >
            <View
              accessible
              accessibilityLabel={[title, texts.label, status].filter(Boolean).join(', ')}
              style={{ gap: Spacing.one }}
            >
              <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>{title}</Text>
              <Amount
                testID={'overview-section-' + section.section + '-amount'}
                value={section.subtotal}
                currency={data.currency}
                masked={hidden}
                valueKnown={valueKnown}
                numberOfLines={1}
                style={{
                  fontSize: size,
                  lineHeight: Math.round(size * 1.5),
                  fontWeight: '700',
                  color: theme.textPrimary,
                }}
              />
            </View>
            <ValueStatusBadge status={section.valueStatus} />
            {!section.complete ? (
              <InfoText warning>{msg(intl, 'total.incomplete')}</InfoText>
            ) : null}
          </View>
        );
      })}
    </View>
  );
}

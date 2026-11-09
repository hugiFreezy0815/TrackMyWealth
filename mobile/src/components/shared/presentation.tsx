import { useState, type ReactNode } from 'react';
import { useIntl } from 'react-intl';
import { Image } from 'expo-image';
import { SymbolView } from 'expo-symbols';
import { Pressable, Text, View } from 'react-native';

import type { DecimalString } from '@/api/decimal';
import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';

import { Amount, amountTexts } from './amount';
import { isValueKnown, type ValueStatus, ValueStatusBadge, valueStatusTexts } from './value-status';

export function SectionCard({
  title,
  children,
  description,
}: {
  title: string;
  description?: string;
  children: ReactNode;
}) {
  const theme = useTheme();
  return (
    <View
      style={{
        padding: Spacing.three,
        borderWidth: 1,
        borderColor: theme.borderDefault,
        borderRadius: Radii.card,
        backgroundColor: theme.bgSurface,
        gap: Spacing.compact,
      }}
    >
      <Text
        accessibilityRole="header"
        style={{ ...TypeScale.headingSmall, color: theme.textPrimary }}
      >
        {title}
      </Text>
      {description ? (
        <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>{description}</Text>
      ) : null}
      {children}
    </View>
  );
}

export function ChangeChip({
  amount,
  currency,
  percentage,
}: {
  amount: DecimalString;
  currency: string;
  percentage?: string | null;
}) {
  const theme = useTheme();
  const intl = useIntl();
  // Direction follows the displayed (rounded) amount, so "-0.001" is a neutral "CHF 0.00". With
  // "Hide amounts" the chip is neutral too: a red or green chip would reveal the sign.
  const hidden = useAmountsHidden();
  const { display } = amountTexts(intl, {
    value: amount,
    currency,
    signRole: 'change',
    masked: hidden,
  });
  const direction = display.startsWith('−') ? 'down' : display.startsWith('+') ? 'up' : 'flat';
  const color =
    direction === 'down'
      ? theme.statusLoss
      : direction === 'up'
        ? theme.statusGain
        : theme.textSecondary;
  const backgroundColor =
    direction === 'down'
      ? theme.statusLossSubtle
      : direction === 'up'
        ? theme.statusGainSubtle
        : theme.bgSubtle;
  return (
    <View
      style={{
        flexDirection: 'row',
        flexWrap: 'wrap',
        alignItems: 'center',
        gap: Spacing.one,
        padding: Spacing.two,
        borderRadius: Radii.pill,
        backgroundColor,
      }}
    >
      {/* The arrow repeats the sign of the amount; screen readers read the amount instead. */}
      <Text
        accessibilityElementsHidden
        importantForAccessibility="no-hide-descendants"
        style={{ ...TypeScale.labelMedium, fontWeight: '600', color }}
      >
        {direction === 'down' ? '↘' : direction === 'up' ? '↗' : '→'}
      </Text>
      <Amount
        value={amount}
        currency={currency}
        signRole="change"
        style={{ ...TypeScale.numberMedium, fontWeight: '600', color }}
      />
      {percentage && !hidden ? (
        <Text style={{ ...TypeScale.numberMedium, color }}>{percentage}</Text>
      ) : null}
    </View>
  );
}

/**
 * Overview spec: the logo from `logoUrl`; when there is none (or it fails to load), the house
 * icon for the personal-assets default portfolio, else the initials of `name`.
 */
export function InstitutionLogo({
  name,
  logoUrl,
  personalAssetsDefault = false,
  size = 44,
}: {
  name: string;
  logoUrl?: string | null;
  personalAssetsDefault?: boolean;
  size?: number;
}) {
  const theme = useTheme();
  const intl = useIntl();
  const [failed, setFailed] = useState(false);
  const box = Math.max(44, size);
  const initials = name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((word) => word[0]?.toUpperCase() ?? '')
    .join('');
  const showHouse = personalAssetsDefault || !initials;
  return (
    <View
      accessible
      accessibilityRole="image"
      accessibilityLabel={name.trim() || intl.formatMessage({ id: 'ui.personalAssets' })}
      style={{
        height: box,
        width: box,
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: theme.bgSubtle,
        borderRadius: Radii.control,
        borderWidth: 1,
        borderColor: theme.borderDefault,
      }}
    >
      {showHouse ? (
        <SymbolView
          name={{ ios: 'house', android: 'home', web: 'home' }}
          size={box / 2}
          tintColor={theme.textPrimary}
        />
      ) : (
        <Text style={{ ...TypeScale.labelMedium, fontWeight: '600', color: theme.textPrimary }}>
          {initials}
        </Text>
      )}
      {/* Remote logos may be blocked or become unavailable: the fallback stays underneath. */}
      {logoUrl?.startsWith('https://') && !failed ? (
        <Image
          source={{ uri: logoUrl }}
          accessible={false}
          onError={() => setFailed(true)}
          contentFit="contain"
          style={{ position: 'absolute', width: box - Spacing.two, height: box - Spacing.two }}
        />
      ) : null}
    </View>
  );
}

export type SummaryRowProps = {
  title: string;
  subtitle?: string;
  amount: DecimalString | null;
  currency: string;
  status?: ValueStatus;
  valueSourceDate?: string | null;
  masked?: boolean;
  onPress?: () => void;
};
export function AccountRow({
  title,
  subtitle,
  amount,
  currency,
  status,
  valueSourceDate,
  masked,
  onPress,
}: SummaryRowProps) {
  const theme = useTheme();
  const intl = useIntl();
  // Spec README §3: an UNKNOWN (or unrecognized) status shows the amount as "–", never a number.
  const valueKnown = isValueKnown(status);
  const hidden = useAmountsHidden();
  const contents = (
    <View
      style={{
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: Spacing.compact,
        justifyContent: 'space-between',
        alignItems: 'center',
      }}
    >
      <View style={{ flexGrow: 1, flexShrink: 1 }}>
        <Text style={{ ...TypeScale.labelMedium, fontWeight: '600', color: theme.textPrimary }}>
          {title}
        </Text>
        {subtitle ? (
          <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>{subtitle}</Text>
        ) : null}
        <ValueStatusBadge status={status} valueSourceDate={valueSourceDate} />
      </View>
      <Amount
        value={amount}
        currency={currency}
        masked={masked}
        valueKnown={valueKnown}
        style={TypeScale.numberLarge}
      />
    </View>
  );
  if (!onPress) return <View style={{ paddingVertical: Spacing.compact }}>{contents}</View>;
  // A pressable row is one screen-reader element, so its label carries everything it shows.
  const label = [
    title,
    subtitle,
    amountTexts(intl, { value: amount, currency, masked: masked || hidden, valueKnown }).label,
    valueStatusTexts(status, valueSourceDate, intl)?.label,
  ]
    .filter(Boolean)
    .join(', ');
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={{ paddingVertical: Spacing.compact, minHeight: 44 }}
    >
      {contents}
    </Pressable>
  );
}

export function TransactionRow({
  date,
  ...props
}: SummaryRowProps & {
  /** Booking date, ISO `YYYY-MM-DD`. */
  date: string;
}) {
  const intl = useIntl();
  return <AccountRow {...props} subtitle={props.subtitle ?? formatDate(date, intl.locale)} />;
}

export function PortfolioTabs({
  tabs,
  selected,
  onSelect,
}: {
  tabs: { id: string; label: string }[];
  selected: string;
  onSelect: (id: string) => void;
}) {
  const theme = useTheme();
  return (
    <View
      accessibilityRole="tablist"
      style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.two }}
    >
      {tabs.map((tab) => (
        <Pressable
          key={tab.id}
          accessibilityRole="tab"
          accessibilityLabel={tab.label}
          accessibilityState={{ selected: selected === tab.id }}
          onPress={() => onSelect(tab.id)}
          style={{
            minHeight: 44,
            justifyContent: 'center',
            borderRadius: Radii.pill,
            paddingHorizontal: Spacing.three,
            backgroundColor: selected === tab.id ? theme.bgInverse : theme.bgSurface,
            borderWidth: 1,
            borderColor: theme.borderInteractive,
          }}
        >
          <Text
            style={{
              ...TypeScale.labelMedium,
              color: selected === tab.id ? theme.bgSurface : theme.textPrimary,
            }}
          >
            {tab.label}
          </Text>
        </Pressable>
      ))}
    </View>
  );
}

export function ChipGroup({
  options,
  selected,
  onSelect,
  multi = false,
  label,
}: {
  options: { value: string; label: string }[];
  selected: string[];
  onSelect: (value: string) => void;
  multi?: boolean;
  /** Accessible name of the group. */
  label?: string;
}) {
  const theme = useTheme();
  return (
    <View
      accessibilityRole={multi ? undefined : 'radiogroup'}
      accessibilityLabel={label}
      style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.two }}
    >
      {options.map((option) => {
        const active = selected.includes(option.value);
        return (
          <Pressable
            key={option.value}
            accessibilityRole={multi ? 'checkbox' : 'radio'}
            accessibilityState={{ checked: active }}
            accessibilityLabel={option.label}
            onPress={() => onSelect(option.value)}
            style={{
              minHeight: 44,
              borderRadius: Radii.pill,
              paddingHorizontal: Spacing.compact,
              justifyContent: 'center',
              borderWidth: 1,
              borderColor: theme.borderInteractive,
              backgroundColor: active ? theme.brandSubtle : theme.bgSurface,
            }}
          >
            <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>
              {option.label}
              {active ? ' ✓' : ''}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}
export const FilterChips = ChipGroup;

export function CountTile({ label, count }: { label: string; count: number | string }) {
  const theme = useTheme();
  return (
    <View
      style={{
        padding: Spacing.three,
        backgroundColor: theme.bgSubtle,
        borderRadius: Radii.card,
        minWidth: 110,
        gap: Spacing.one,
      }}
    >
      <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>{label}</Text>
      <Text style={{ ...TypeScale.headingLarge, color: theme.textPrimary }}>{count}</Text>
    </View>
  );
}

export function KeyValueList({
  items,
}: {
  items: { key: string; label: string; value: ReactNode }[];
}) {
  const theme = useTheme();
  return (
    <View style={{ gap: Spacing.compact }}>
      {items.map((item) => (
        <View
          key={item.key}
          style={{
            flexDirection: 'row',
            flexWrap: 'wrap',
            gap: Spacing.two,
            justifyContent: 'space-between',
          }}
        >
          <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>{item.label}</Text>
          <View style={{ flexShrink: 1 }}>
            {typeof item.value === 'string' || typeof item.value === 'number' ? (
              <Text style={{ ...TypeScale.bodyMedium, color: theme.textPrimary }}>
                {item.value}
              </Text>
            ) : (
              item.value
            )}
          </View>
        </View>
      ))}
    </View>
  );
}

export function Stepper({
  steps,
  current,
  onSelect,
}: {
  steps: string[];
  current: number;
  onSelect?: (step: number) => void;
}) {
  const theme = useTheme();
  const intl = useIntl();
  return (
    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.two }}>
      {steps.map((step, index) => {
        const disabled = !onSelect || index > current;
        return (
          <Pressable
            key={index}
            accessibilityRole="button"
            accessibilityState={{ selected: current === index, disabled }}
            disabled={disabled}
            accessibilityLabel={index + 1 + '. ' + step}
            accessibilityHint={disabled ? undefined : intl.formatMessage({ id: 'ui.select' })}
            onPress={() => onSelect?.(index)}
            style={{
              minHeight: 44,
              flexDirection: 'row',
              alignItems: 'center',
              gap: Spacing.two,
              paddingHorizontal: Spacing.compact,
              backgroundColor: current === index ? theme.brandSubtle : theme.bgSurface,
              borderRadius: Radii.control,
              borderWidth: 1,
              borderColor: theme.borderDefault,
            }}
          >
            <Text style={{ ...TypeScale.labelMedium, fontWeight: '600', color: theme.textPrimary }}>
              {index + 1}
            </Text>
            <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>{step}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

import { useState, type ReactNode } from 'react';
import { Image } from 'expo-image';
import { Pressable, Text, View } from 'react-native';

import type { DecimalString } from '@/api/decimal';
import { Radii, Spacing } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

import { Amount } from './amount';
import { currentUiLocale, uiMessage, type UiLocale } from './locale';
import { type ValueStatus, ValueStatusBadge } from './value-status';

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
        gap: 12,
      }}
    >
      <Text
        accessibilityRole="header"
        style={{ fontSize: 16, fontWeight: '600', color: theme.textPrimary }}
      >
        {title}
      </Text>
      {description ? <Text style={{ color: theme.textSecondary }}>{description}</Text> : null}
      {children}
    </View>
  );
}

export function ChangeChip({
  amount,
  currency,
  percentage,
  locale = currentUiLocale(),
}: {
  amount: DecimalString;
  currency: string;
  percentage?: string | null;
  locale?: UiLocale;
}) {
  const theme = useTheme();
  const negative = amount.startsWith('-') && !/^-0+(\.0+)?$/.test(amount);
  const color = negative ? theme.statusLoss : theme.statusGain;
  const backgroundColor = negative ? theme.statusLossSubtle : theme.statusGainSubtle;
  return (
    <View
      style={{
        flexDirection: 'row',
        flexWrap: 'wrap',
        alignItems: 'center',
        gap: 4,
        padding: 8,
        borderRadius: Radii.pill,
        backgroundColor,
      }}
    >
      <Text style={{ color, fontWeight: '600' }}>{negative ? '↘' : '↗'}</Text>
      <Amount
        value={amount}
        currency={currency}
        signRole="change"
        locale={locale}
        style={{ color, fontWeight: '600' }}
      />
      {percentage ? <Text style={{ color }}>{percentage}</Text> : null}
    </View>
  );
}
export function InstitutionLogo({
  name,
  logoUrl,
  size = 44,
}: {
  name: string;
  logoUrl?: string | null;
  size?: number;
}) {
  const theme = useTheme();
  const [failed, setFailed] = useState(false);
  // Remote logos may be blocked / become unavailable. Initials are always visible.
  const initials =
    name
      .trim()
      .split(/\s+/)
      .slice(0, 2)
      .map((word) => word[0]?.toUpperCase() ?? '')
      .join('') || '⌂';
  return (
    <View
      accessibilityLabel={name}
      style={{
        height: Math.max(44, size),
        width: Math.max(44, size),
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: theme.bgSubtle,
        borderRadius: Radii.control,
        borderWidth: 1,
        borderColor: theme.borderDefault,
      }}
    >
      <Text style={{ color: theme.textPrimary, fontWeight: '600' }}>{initials}</Text>
      {logoUrl?.startsWith('https://') && !failed ? (
        <Image
          source={{ uri: logoUrl }}
          accessibilityLabel={name}
          onError={() => setFailed(true)}
          contentFit="contain"
          style={{
            position: 'absolute',
            width: Math.max(44, size) - 8,
            height: Math.max(44, size) - 8,
          }}
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
  masked?: boolean;
  onPress?: () => void;
  locale?: UiLocale;
};
export function AccountRow({
  title,
  subtitle,
  amount,
  currency,
  status,
  masked,
  onPress,
  locale = currentUiLocale(),
}: SummaryRowProps) {
  const theme = useTheme();
  const contents = (
    <View
      style={{
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 12,
        justifyContent: 'space-between',
        alignItems: 'center',
      }}
    >
      <View style={{ flexGrow: 1, flexShrink: 1 }}>
        <Text style={{ fontWeight: '600', color: theme.textPrimary }}>{title}</Text>
        {subtitle ? <Text style={{ color: theme.textSecondary }}>{subtitle}</Text> : null}
        <ValueStatusBadge status={status} locale={locale} />
      </View>
      <Amount value={amount} currency={currency} masked={masked} locale={locale} />
    </View>
  );
  if (!onPress) return <View style={{ paddingVertical: Spacing.compact }}>{contents}</View>;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={title}
      onPress={onPress}
      style={{ paddingVertical: Spacing.compact, minHeight: 44 }}
    >
      {contents}
    </Pressable>
  );
}
export function TransactionRow(props: SummaryRowProps & { date: string }) {
  return <AccountRow {...props} subtitle={props.subtitle ?? props.date} />;
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
    <View accessibilityRole="tablist" style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
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
            paddingHorizontal: 16,
            backgroundColor: selected === tab.id ? theme.bgInverse : theme.bgSurface,
            borderWidth: 1,
            borderColor: theme.borderInteractive,
          }}
        >
          <Text style={{ color: selected === tab.id ? theme.bgSurface : theme.textPrimary }}>
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
}: {
  options: { value: string; label: string }[];
  selected: string[];
  onSelect: (value: string) => void;
  multi?: boolean;
}) {
  const theme = useTheme();
  return (
    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
      {options.map((option) => {
        const active = selected.includes(option.value);
        return (
          <Pressable
            key={option.value}
            accessibilityRole={multi ? 'checkbox' : 'radio'}
            accessibilityState={multi ? { checked: active } : { selected: active }}
            onPress={() => onSelect(option.value)}
            style={{
              minHeight: 44,
              borderRadius: Radii.pill,
              paddingHorizontal: 12,
              justifyContent: 'center',
              borderWidth: 1,
              borderColor: theme.borderInteractive,
              backgroundColor: active ? theme.brandSubtle : theme.bgSurface,
            }}
          >
            <Text style={{ color: theme.textPrimary }}>
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
        gap: 4,
      }}
    >
      <Text style={{ color: theme.textSecondary }}>{label}</Text>
      <Text style={{ color: theme.textPrimary, fontSize: 24, fontWeight: '600' }}>{count}</Text>
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
    <View style={{ gap: 12 }}>
      {items.map((item) => (
        <View
          key={item.key}
          style={{
            flexDirection: 'row',
            flexWrap: 'wrap',
            gap: 8,
            justifyContent: 'space-between',
          }}
        >
          <Text style={{ color: theme.textSecondary }}>{item.label}</Text>
          <View style={{ flexShrink: 1 }}>
            {typeof item.value === 'string' || typeof item.value === 'number' ? (
              <Text style={{ color: theme.textPrimary }}>{item.value}</Text>
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
  locale = currentUiLocale(),
}: {
  steps: string[];
  current: number;
  onSelect?: (step: number) => void;
  locale?: UiLocale;
}) {
  const theme = useTheme();
  return (
    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
      {steps.map((step, index) => (
        <Pressable
          key={index}
          accessibilityRole="button"
          accessibilityState={{
            selected: current === index,
            disabled: !onSelect || index > current,
          }}
          disabled={!onSelect || index > current}
          accessibilityLabel={index + 1 + '. ' + step}
          accessibilityHint={uiMessage('ui.select', locale)}
          onPress={() => onSelect?.(index)}
          style={{
            minHeight: 44,
            flexDirection: 'row',
            alignItems: 'center',
            gap: 6,
            paddingHorizontal: 12,
            backgroundColor: current === index ? theme.brandSubtle : theme.bgSurface,
            borderRadius: Radii.control,
            borderWidth: 1,
            borderColor: theme.borderDefault,
          }}
        >
          <Text style={{ fontWeight: '600', color: theme.textPrimary }}>{index + 1}</Text>
          <Text style={{ color: theme.textPrimary }}>{step}</Text>
        </Pressable>
      ))}
    </View>
  );
}

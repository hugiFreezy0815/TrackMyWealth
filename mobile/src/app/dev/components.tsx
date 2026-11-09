import { Redirect } from 'expo-router';
import { useState } from 'react';
import { ScrollView, Text, View } from 'react-native';

import { DesignTokens, Radii, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import {
  AccountRow,
  Amount,
  AmountField,
  ChangeChip,
  ChipGroup,
  BottomSheet,
  ConflictDialog,
  CountTile,
  CurrencyPicker,
  DateField,
  Dialog,
  EmptyState,
  ErrorBanner,
  InstitutionLogo,
  KeyValueList,
  LoadingSkeleton,
  OfflineBanner,
  PortfolioTabs,
  SectionCard,
  Stepper,
  TextField,
  TransactionRow,
  ValueStatusBadge,
} from '@/components/shared';

/**
 * Synthetic and development-only component review. This screen has no API calls,
 * account identifiers or production navigation entry. Guarded even for deep links.
 */
export default function ComponentGallery() {
  const theme = useTheme();
  const [selected, setSelected] = useState('all');
  const [chip, setChip] = useState('one');
  const [amount, setAmount] = useState<string | null>('1234.5');
  const [date, setDate] = useState<string | null>('2026-11-08');
  const [currency, setCurrency] = useState('CHF');
  const [conflict, setConflict] = useState(false);
  const [dialog, setDialog] = useState<'dialog' | 'sheet' | null>(null);
  if (!__DEV__) return <Redirect href="/" />;

  return (
    <ScrollView
      style={{ flex: 1, backgroundColor: theme.bgCanvas }}
      contentContainerStyle={{ padding: Spacing.three, gap: Spacing.three }}
    >
      <Text
        accessibilityRole="header"
        style={{ ...TypeScale.headingLarge, color: theme.textPrimary }}
      >
        Shared components (synthetic values)
      </Text>
      <SectionCard title="Palette and tokens" description="Light/dark follows the device theme.">
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
          {Object.entries(DesignTokens.light)
            .slice(0, 18)
            .map(([key]) => (
              <View key={key} style={{ width: 120, gap: 4 }}>
                <View
                  style={{
                    height: 36,
                    borderRadius: Radii.control,
                    borderWidth: 1,
                    borderColor: theme.borderDefault,
                    backgroundColor: theme[key as keyof typeof DesignTokens.light],
                  }}
                />
                <Text style={{ color: theme.textSecondary, fontSize: 12 }}>{key}</Text>
              </View>
            ))}
        </View>
      </SectionCard>
      <SectionCard title="Amounts and changes">
        <Amount value="-1234.5" currency="CHF" locale="de-CH" />
        <Amount value="12345678901234567.89" currency="CHF" locale="de-CH" />
        <Amount value="9999" currency="CHF" masked />
        <Amount value={null} currency="EUR" />
        <Amount value="1234.5" currency="CHF" valueKnown={false} />
        <Amount value="1234.5" currency="NOT-A-CODE" />
        <ChangeChip amount="-0.001" currency="CHF" locale="de-CH" />
        <ChangeChip amount="-312.4" currency="EUR" percentage="−0.8%" locale="de-DE" />
        <ChangeChip amount="6842.1" currency="CHF" percentage="+1.45%" locale="de-CH" />
      </SectionCard>
      <SectionCard title="Value status (all states)">
        {(
          ['CURRENT', 'ESTIMATED', 'STALE', 'UNRECONCILED', 'UNKNOWN', 'FUTURE_VALUE'] as const
        ).map((status) => (
          <View key={status} style={{ gap: 4 }}>
            <Text style={{ color: theme.textSecondary }}>{status}</Text>
            <ValueStatusBadge status={status} valueSourceDate="2026-10-01" />
          </View>
        ))}
      </SectionCard>
      <SectionCard title="Portfolio navigation">
        <PortfolioTabs
          tabs={[
            { id: 'all', label: 'All portfolios' },
            { id: 'one', label: 'Example' },
          ]}
          selected={selected}
          onSelect={setSelected}
        />
        <ChipGroup
          options={[
            { value: 'one', label: 'Cash' },
            { value: 'two', label: 'Depot' },
          ]}
          selected={[chip]}
          onSelect={setChip}
        />
      </SectionCard>
      <SectionCard title="Rows and totals">
        <View style={{ flexDirection: 'row', gap: Spacing.two }}>
          <InstitutionLogo name="Example Bank" />
          <InstitutionLogo name="Personal assets" personalAssetsDefault />
        </View>
        <AccountRow
          title="Sample account"
          subtitle="Cash"
          amount="1234.50"
          currency="CHF"
          status="ESTIMATED"
          onPress={() => {}}
        />
        <AccountRow title="Unknown value" amount="99" currency="CHF" status="UNKNOWN" />
        <TransactionRow
          title="Sample transaction"
          date="2026-11-08"
          amount="-14.99"
          currency="CHF"
        />
        <CountTile label="Transactions" count={12} />
        <KeyValueList items={[{ key: 'a', label: 'Source', value: 'Sample only' }]} />
      </SectionCard>
      <SectionCard title="Form controls">
        <TextField label="Sample name" value="Test" onChangeText={() => {}} />
        <AmountField
          label="Opening balance"
          locale="de-CH"
          currency={currency}
          value={amount}
          onChangeValue={setAmount}
        />
        <Text style={{ color: theme.textSecondary }}>DecimalString: {String(amount)}</Text>
        <DateField label="Booking date" value={date} onChange={setDate} />
        <CurrencyPicker
          label="Currency"
          value={currency}
          onChange={setCurrency}
          options={[
            { value: 'CHF', label: 'CHF' },
            { value: 'EUR', label: 'EUR' },
          ]}
        />
        <Stepper steps={['Create', 'Review', 'Finish']} current={1} />
      </SectionCard>
      <SectionCard title="States and errors">
        <EmptyState description="Example empty state" />
        <ErrorBanner onRetry={() => {}} />
        <OfflineBanner lastUpdated="2026-11-08T12:00:00Z" />
        <LoadingSkeleton />
        <Text
          accessibilityRole="button"
          onPress={() => setConflict(true)}
          style={{ color: theme.brandLink, padding: 14 }}
        >
          Show conflict dialog
        </Text>
        <Text
          accessibilityRole="button"
          onPress={() => setDialog('dialog')}
          style={{ color: theme.brandLink, padding: 14 }}
        >
          Show dialog
        </Text>
        <Text
          accessibilityRole="button"
          onPress={() => setDialog('sheet')}
          style={{ color: theme.brandLink, padding: 14 }}
        >
          Show bottom sheet
        </Text>
      </SectionCard>
      <ConflictDialog
        visible={conflict}
        onClose={() => setConflict(false)}
        onReview={() => setConflict(false)}
        onDiscard={() => setConflict(false)}
      />
      <Dialog title="Sample dialog" visible={dialog === 'dialog'} onClose={() => setDialog(null)}>
        <Text style={{ color: theme.textSecondary }}>Synthetic dialog content.</Text>
      </Dialog>
      <BottomSheet title="Sample sheet" visible={dialog === 'sheet'} onClose={() => setDialog(null)}>
        <Text style={{ color: theme.textSecondary }}>Synthetic sheet content.</Text>
      </BottomSheet>
    </ScrollView>
  );
}

import { Redirect } from 'expo-router';
import { useState } from 'react';
import { IntlProvider } from 'react-intl';
import { ScrollView, Text, View } from 'react-native';

import { DesignTokens, Radii, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import { messagesFor, type AppLocale } from '@/i18n';
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
  const [locale, setLocale] = useState<AppLocale>('de-CH');
  const [selected, setSelected] = useState('all');
  const [chip, setChip] = useState('one');
  const [amount, setAmount] = useState<string | null>('1234.5');
  const [amountInvalid, setAmountInvalid] = useState(false);
  const [date, setDate] = useState<string | null>('2026-11-08');
  const [noDate, setNoDate] = useState<string | null>(null);
  const [currency, setCurrency] = useState('CHF');
  const [conflict, setConflict] = useState(false);
  const [dialog, setDialog] = useState<'dialog' | 'sheet' | null>(null);
  if (!__DEV__) return <Redirect href="/" />;

  return (
    <IntlProvider locale={locale} defaultLocale="en" messages={messagesFor(locale)}>
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
        <ChipGroup
          label="Language"
          options={[
            { value: 'en', label: 'EN' },
            { value: 'de-CH', label: 'de-CH' },
            { value: 'de-DE', label: 'de-DE' },
          ]}
          selected={[locale]}
          onSelect={(value) => setLocale(value as AppLocale)}
        />
        <SectionCard title="Palette and tokens" description="Light/dark follows the device theme.">
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.two }}>
            {Object.entries(DesignTokens.light)
              .slice(0, 18)
              .map(([key]) => (
                <View key={key} style={{ width: 120, gap: Spacing.one }}>
                  <View
                    style={{
                      height: 36,
                      borderRadius: Radii.control,
                      borderWidth: 1,
                      borderColor: theme.borderDefault,
                      backgroundColor: theme[key as keyof typeof DesignTokens.light],
                    }}
                  />
                  <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>{key}</Text>
                </View>
              ))}
          </View>
        </SectionCard>
        <SectionCard title="Amounts and changes">
          <Amount value="-1234.5" currency="CHF" />
          <Amount value="12345678901234567.89" currency="CHF" />
          <Amount value="9999" currency="CHF" masked />
          <Amount value={null} currency="EUR" />
          <Amount value="1234.5" currency="CHF" valueKnown={false} />
          <Amount value="1234.5" currency="NOT-A-CODE" />
          <Amount value="1200" currency="CHF" approx />
          <ChangeChip amount="-0.001" currency="CHF" />
          <ChangeChip amount="-312.4" currency="EUR" percentage="−0.8%" />
          <ChangeChip amount="6842.1" currency="CHF" percentage="+1.45%" />
        </SectionCard>
        <SectionCard title="Value status (all states)">
          {(
            ['CURRENT', 'ESTIMATED', 'STALE', 'UNRECONCILED', 'UNKNOWN', 'FUTURE_VALUE'] as const
          ).map((status) => (
            <View key={status} style={{ gap: Spacing.one }}>
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
            currency={currency}
            value={amount}
            onChangeValue={(value, { invalid }) => {
              setAmount(value);
              setAmountInvalid(invalid);
            }}
          />
          <Text style={{ color: theme.textSecondary }}>
            DecimalString: {String(amount)}
            {amountInvalid ? ' (invalid input: submit blocked)' : ''}
          </Text>
          <DateField
            label="Booking date"
            value={date}
            onChange={setDate}
            maximumDate="2026-12-31"
          />
          <DateField
            label="Optional date"
            value={noDate}
            onChange={setNoDate}
            onClear={() => setNoDate(null)}
          />
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
            style={{
              ...TypeScale.labelMedium,
              color: theme.brandLink,
              padding: Spacing.compact,
              minHeight: 44,
            }}
          >
            Show conflict dialog
          </Text>
          <Text
            accessibilityRole="button"
            onPress={() => setDialog('dialog')}
            style={{
              ...TypeScale.labelMedium,
              color: theme.brandLink,
              padding: Spacing.compact,
              minHeight: 44,
            }}
          >
            Show dialog
          </Text>
          <Text
            accessibilityRole="button"
            onPress={() => setDialog('sheet')}
            style={{
              ...TypeScale.labelMedium,
              color: theme.brandLink,
              padding: Spacing.compact,
              minHeight: 44,
            }}
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
        <BottomSheet
          title="Sample sheet"
          visible={dialog === 'sheet'}
          onClose={() => setDialog(null)}
        >
          <Text style={{ color: theme.textSecondary }}>Synthetic sheet content.</Text>
        </BottomSheet>
      </ScrollView>
    </IntlProvider>
  );
}

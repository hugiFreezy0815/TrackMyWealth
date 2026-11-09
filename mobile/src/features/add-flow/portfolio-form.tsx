import { useEffect, useMemo, useRef, useState, type ComponentRef } from 'react';
import { useIntl } from 'react-intl';
import { Pressable, ScrollView, Text, View } from 'react-native';

import {
  ActionButton,
  ChipGroup,
  ChoiceField,
  ErrorBanner,
  InstitutionLogo,
  LoadingSkeleton,
  SelectField,
  TextField,
  moveAccessibilityFocus,
} from '@/components/shared';
import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { useOnline } from '@/hooks/use-online';
import { useTheme } from '@/hooks/use-theme';
import { countryName, countryOptions, currencyOptions } from '@/i18n/iso';

import {
  CATALOGUE_COUNTRIES,
  INSTITUTION_TYPES,
  MAX_PORTFOLIO_NAME_LENGTH,
  importFormatKey,
  institutionTypeKey,
  preferredCurrency,
  validPortfolioName,
  validationErrors,
  type Institution,
  type Portfolio,
  type PortfolioRequest,
} from './model';
import { useCreatePortfolio, useInstitutionCatalogue } from './use-create';

/** S5: the search waits this long after the last keystroke before it asks the server. */
export const SEARCH_DEBOUNCE_MS = 300;
const ALL_COUNTRIES = 'ALL';
const LIKELY_CURRENCIES = ['CHF', 'EUR', 'USD', 'GBP'];

/** S5 Add portfolio (US-04-11, add-portfolio.md): pick an institution or create your own. */
export function PortfolioForm({
  workspaceCurrency,
  onCreated,
  onCancel,
}: {
  workspaceCurrency: string;
  onCreated: (portfolio: Portfolio) => void;
  onCancel: () => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const online = useOnline();
  const msg = (id: string, values?: Record<string, string | number>) =>
    intl.formatMessage({ id }, values);
  const [search, setSearch] = useState('');
  const [debounced, setDebounced] = useState('');
  const [countryFilter, setCountryFilter] = useState<string | null>(null);
  const [selected, setSelected] = useState<Institution | null>(null);
  const [own, setOwn] = useState(false);
  const [name, setName] = useState('');
  const [country, setCountry] = useState('CH');
  const [institutionType, setInstitutionType] = useState<string>('BANK');
  const [currency, setCurrency] = useState(workspaceCurrency);
  const create = useCreatePortfolio();
  // Guards the non-idempotent POST: a second tap can arrive before `isPending` re-renders.
  const busy = useRef(false);
  const sheetHeading = useRef<ComponentRef<typeof View>>(null);

  useEffect(() => {
    const timeout = setTimeout(() => setDebounced(search), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timeout);
  }, [search]);

  // "The bottom sheet takes focus when it opens" (S5 accessibility).
  const sheetFor = own ? 'own' : (selected?.id ?? null);
  useEffect(() => {
    if (sheetFor) moveAccessibilityFocus(sheetHeading.current);
  }, [sheetFor]);

  const list = useInstitutionCatalogue(debounced, countryFilter);
  const rows = list.data?.pages.flatMap((page) => page.content) ?? [];
  // Grouped under the server's order (name, asc); nothing is filtered on the client.
  const countries = Array.from(new Set(rows.map((row) => row.country)));
  const currencies = useMemo(
    () => currencyOptions(intl.locale, [workspaceCurrency, ...LIKELY_CURRENCIES]),
    [intl.locale, workspaceCurrency],
  );
  const countryChoices = useMemo(
    () => countryOptions(intl.locale, CATALOGUE_COUNTRIES),
    [intl.locale],
  );
  const errors = validationErrors(create.error);
  const fieldError = (...fields: string[]) =>
    fields.some((field) => errors[field]) ? msg('addFlow.fieldError') : undefined;
  const nameTooLong = name.trim().length > MAX_PORTFOLIO_NAME_LENGTH;
  const canCreate =
    online &&
    !create.isPending &&
    (own || selected !== null) &&
    validPortfolioName(name, own) &&
    /^[A-Z]{3}$/.test(currency) &&
    (!own || /^[A-Z]{2}$/.test(country));
  // With a catalogue entry a blank name falls back to the entry's name, as the server does.
  const tabName = name.trim() || selected?.name || '';

  const choose = (institution: Institution) => {
    setSelected(institution);
    setOwn(false);
    setName(institution.name);
    setCurrency(preferredCurrency(institution.country, workspaceCurrency));
    create.reset();
  };
  const chooseOwn = () => {
    const ownCountry = countryFilter ?? 'CH';
    setOwn(true);
    setSelected(null);
    setName(search.trim());
    setCountry(ownCountry);
    setCurrency(preferredCurrency(ownCountry, workspaceCurrency));
    create.reset();
  };

  function request(): PortfolioRequest {
    const trimmed = name.trim();
    if (own) {
      return { name: trimmed, country, institutionType, portfolioCurrency: currency };
    }
    return {
      catalogueInstitutionId: selected!.id,
      ...(trimmed ? { name: trimmed } : {}),
      portfolioCurrency: currency,
    };
  }

  async function submit() {
    if (!canCreate || busy.current) return;
    busy.current = true;
    try {
      onCreated(await create.mutateAsync(request()));
    } catch {
      // The error stays rendered; a non-idempotent POST is never retried automatically.
    } finally {
      busy.current = false;
    }
  }

  return (
    <ScrollView
      keyboardShouldPersistTaps="handled"
      contentContainerStyle={{ gap: Spacing.three, paddingBottom: Spacing.four }}
      onScroll={(event) => {
        const { contentOffset, layoutMeasurement, contentSize } = event.nativeEvent;
        if (
          contentOffset.y + layoutMeasurement.height >= contentSize.height - 180 &&
          list.hasNextPage &&
          !list.isFetchingNextPage
        ) {
          void list.fetchNextPage();
        }
      }}
      scrollEventThrottle={120}
    >
      <Text style={{ ...TypeScale.labelMedium, color: theme.textSecondary }}>
        {msg('addFlow.step', { current: 1, total: 2 })}
      </Text>
      <Text
        accessibilityRole="header"
        style={{ ...TypeScale.headingLarge, color: theme.textPrimary }}
      >
        {msg('addPortfolio.heading')}
      </Text>
      <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
        {msg('addPortfolio.prompt')}
      </Text>
      <TextField
        label={msg('addPortfolio.search.label')}
        value={search}
        onChangeText={setSearch}
        placeholder={msg('addPortfolio.search.placeholder')}
        testID="catalogue-search"
        autoCapitalize="none"
        autoCorrect={false}
      />
      <ChipGroup
        label={msg('addPortfolio.countries')}
        options={[
          { value: ALL_COUNTRIES, label: msg('addPortfolio.countries.all') },
          ...CATALOGUE_COUNTRIES.map((code) => ({
            value: code,
            label: countryName(code, intl.locale),
          })),
        ]}
        selected={[countryFilter ?? ALL_COUNTRIES]}
        onSelect={(value) => setCountryFilter(value === ALL_COUNTRIES ? null : value)}
      />
      {list.isPending ? <LoadingSkeleton count={3} height={44} /> : null}
      {list.isError ? <ErrorBanner error={list.error} onRetry={() => void list.refetch()} /> : null}
      {list.isSuccess && rows.length === 0 ? (
        <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
          {msg('addPortfolio.noMatch', { query: debounced.trim() })}
        </Text>
      ) : null}
      <View
        accessibilityRole="radiogroup"
        accessibilityLabel={msg('addPortfolio.institution.label')}
        style={{ gap: Spacing.two }}
      >
        {countries.map((code) => (
          <View key={code} style={{ gap: Spacing.one }}>
            <Text
              accessibilityRole="header"
              style={{
                ...TypeScale.overline,
                textTransform: 'uppercase',
                color: theme.textSecondary,
              }}
            >
              {countryName(code, intl.locale)}
            </Text>
            {rows
              .filter((row) => row.country === code)
              .map((row) => (
                <CatalogueRow
                  key={row.id}
                  institution={row}
                  active={selected?.id === row.id && !own}
                  onPress={() => choose(row)}
                />
              ))}
          </View>
        ))}
        {list.hasNextPage ? (
          <ActionButton
            label={msg('addPortfolio.loadMore')}
            onPress={() => void list.fetchNextPage()}
            disabled={list.isFetchingNextPage}
          />
        ) : null}
        <Pressable
          accessibilityRole="radio"
          accessibilityLabel={msg('addPortfolio.createOwn')}
          aria-checked={own}
          onPress={chooseOwn}
          style={{ minHeight: 44, justifyContent: 'center' }}
        >
          <Text style={{ ...TypeScale.labelMedium, color: theme.brandLink }}>
            {msg('addPortfolio.createOwn')}
            {own ? ' ✓' : ''}
          </Text>
        </Pressable>
      </View>
      {selected || own ? (
        <View
          style={{
            gap: Spacing.three,
            padding: Spacing.three,
            backgroundColor: theme.bgSurface,
            borderRadius: Radii.card,
          }}
        >
          {/* Focus lands here when the panel opens; a View, since only it takes tabIndex. */}
          <View ref={sheetHeading} tabIndex={-1} accessible accessibilityRole="header">
            <Text style={{ ...TypeScale.labelMedium, color: theme.textSecondary }}>
              {msg('addPortfolio.newTab')}
            </Text>
          </View>
          {/* A picture of the future tab, not a tab: it is read as one text. */}
          <View
            accessible
            accessibilityLabel={msg('addPortfolio.newTab.preview', { name: tabName })}
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              alignSelf: 'flex-start',
              gap: Spacing.two,
              padding: Spacing.two,
              borderRadius: Radii.pill,
              backgroundColor: theme.bgSubtle,
            }}
          >
            <InstitutionLogo name={tabName} logoUrl={selected?.logoUrl} />
            <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary, flexShrink: 1 }}>
              {tabName}
            </Text>
          </View>
          <TextField
            label={msg('addPortfolio.name')}
            value={name}
            onChangeText={setName}
            maxLength={MAX_PORTFOLIO_NAME_LENGTH + 1}
            hint={
              own ? undefined : msg('addPortfolio.name.optional', { name: selected?.name ?? '' })
            }
            error={nameTooLong ? msg('addPortfolio.invalidName') : fieldError('name')}
            testID="portfolio-name"
          />
          {own ? (
            <>
              <SelectField
                label={msg('addPortfolio.own.country')}
                value={country}
                options={countryChoices}
                onChange={(next) => {
                  setCountry(next);
                  setCurrency(preferredCurrency(next, workspaceCurrency));
                }}
                error={fieldError('country')}
              />
              <ChoiceField
                label={msg('addPortfolio.own.type')}
                value={institutionType}
                options={INSTITUTION_TYPES.map((value) => ({
                  value,
                  label: msg(institutionTypeKey(value)),
                }))}
                onChange={setInstitutionType}
                error={fieldError('institutionType')}
              />
            </>
          ) : null}
          <SelectField
            label={msg('addPortfolio.currency')}
            value={currency}
            options={currencies}
            onChange={setCurrency}
            hint={msg('addPortfolio.currency.help')}
            error={fieldError('portfolioCurrency')}
          />
          {create.isError ? <ErrorBanner error={create.error} /> : null}
          {!online ? (
            <Text accessibilityRole="alert" style={{ color: theme.statusWarning }}>
              {msg('addFlow.offline')}
            </Text>
          ) : null}
          <ActionButton
            label={msg('addPortfolio.create')}
            onPress={() => void submit()}
            disabled={!canCreate}
          />
        </View>
      ) : null}
      <Pressable
        accessibilityRole="button"
        onPress={onCancel}
        style={{ minHeight: 44, justifyContent: 'center' }}
      >
        <Text style={{ ...TypeScale.labelMedium, color: theme.brandLink }}>
          {msg('addFlow.cancel')}
        </Text>
      </Pressable>
    </ScrollView>
  );
}

function CatalogueRow({
  institution,
  active,
  onPress,
}: {
  institution: Institution;
  active: boolean;
  onPress: () => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const details = [
    intl.formatMessage({ id: institutionTypeKey(institution.institutionType) }),
    ...institution.importFormats.map((format) =>
      intl.formatMessage({ id: importFormatKey(format) }),
    ),
  ].join(' · ');
  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityLabel={institution.name}
      accessibilityHint={details}
      aria-checked={active}
      onPress={onPress}
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        minHeight: 56,
        gap: Spacing.two,
        padding: Spacing.two,
        borderRadius: Radii.control,
        borderWidth: 1,
        borderColor: active ? theme.brandPrimary : theme.borderDefault,
        backgroundColor: active ? theme.brandSubtle : theme.bgSurface,
      }}
    >
      <InstitutionLogo name={institution.name} logoUrl={institution.logoUrl} />
      <View style={{ flex: 1, minWidth: 0 }}>
        <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>
          {institution.name}
        </Text>
        <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>{details}</Text>
      </View>
      <Text
        accessibilityElementsHidden
        importantForAccessibility="no"
        style={{ color: theme.brandLink }}
      >
        {active ? '✓' : '›'}
      </Text>
    </Pressable>
  );
}

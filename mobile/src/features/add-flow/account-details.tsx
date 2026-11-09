import { useMemo, useState } from 'react';
import { useIntl } from 'react-intl';
import { Switch, Text, View } from 'react-native';

import { AmountField, ChipGroup, ChoiceField, SelectField, TextField } from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import { currencyOptions } from '@/i18n/iso';

import { identifierRejected, type AccountDraft } from './account-draft';
import {
  ACCOUNT_TYPES,
  CUSTOM_ASSET_TYPES,
  PENSION_SCHEMES,
  formatIdentifierInput,
  identifierLabelKey,
  identifierOnBlur,
  needsIdentifier,
  needsPrincipal,
  type AccountType,
  type IdentifierKind,
} from './model';

/** The `nativeID` of the type warning the chip groups refer to. */
const TYPE_WARNING_ID = 'add-account-type-warning';

/** Currencies offered first: the portfolio's, then the ones most members hold. */
const LIKELY_CURRENCIES = ['CHF', 'EUR', 'USD', 'GBP'];

/** S6 step 1 (M17): the type chips and the fields of the chosen type. */
export function AccountDetails({
  draft,
  portfolioCurrency,
  disabled,
  errors,
  onChange,
  onChooseType,
}: {
  draft: AccountDraft;
  portfolioCurrency: string;
  disabled: boolean;
  /** Message ids by request field, from a rejected request. */
  errors: Record<string, string>;
  onChange: (patch: Partial<AccountDraft>) => void;
  onChooseType: (type: AccountType) => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const msg = (id: string) => intl.formatMessage({ id });
  const fieldError = (field: string) => (errors[field] ? msg(errors[field]) : undefined);
  const currencies = useMemo(
    () => currencyOptions(intl.locale, [portfolioCurrency, ...LIKELY_CURRENCIES]),
    [intl.locale, portfolioCurrency],
  );
  const type = draft.accountType;
  const warning = msg('addAccount.type.warning');
  const required = msg('addFlow.required');

  return (
    <>
      <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>
        {msg('addAccount.type.label')}
      </Text>
      {(['ASSET', 'LIABILITY'] as const).map((group) => (
        <View key={group} style={{ gap: Spacing.two }}>
          <Text
            accessibilityRole="header"
            style={{ ...TypeScale.labelMedium, color: theme.textSecondary }}
          >
            {msg(`addAccount.group.${group}`)}
          </Text>
          <ChipGroup
            label={msg(`addAccount.group.${group}`)}
            options={ACCOUNT_TYPES.filter((item) => item.group === group).map((item) => ({
              value: item.type,
              label: msg(item.key),
            }))}
            selected={[type]}
            onSelect={(value) => onChooseType(value as AccountType)}
            disabled={disabled}
            describedBy={{ id: TYPE_WARNING_ID, text: warning }}
          />
        </View>
      ))}
      <Text nativeID={TYPE_WARNING_ID} style={{ ...TypeScale.caption, color: theme.statusWarning }}>
        {warning}
      </Text>
      <TextField
        label={msg('addAccount.name')}
        testID="account-name"
        value={draft.name}
        editable={!disabled}
        onChangeText={(name) => onChange({ name, nameEdited: true })}
        error={fieldError('name')}
      />
      <SelectField
        label={msg('addAccount.currency')}
        value={draft.currency}
        options={currencies}
        onChange={(currency) => onChange({ currency })}
        disabled={disabled}
        hint={msg('addAccount.currency.immutable')}
        error={fieldError('nativeCurrency')}
      />
      {type === 'SAVINGS' ? (
        <View
          style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}
        >
          <Text style={{ ...TypeScale.bodyMedium, color: theme.textPrimary }}>
            {msg('addAccount.saving')}
          </Text>
          <Switch
            accessibilityLabel={msg('addAccount.saving')}
            value={draft.countsAsSaving}
            disabled={disabled}
            onValueChange={(countsAsSaving) => onChange({ countsAsSaving })}
          />
        </View>
      ) : null}
      {type === 'PENSION' ? (
        <ChoiceField
          label={msg('addAccount.pensionScheme')}
          value={draft.pensionScheme}
          options={PENSION_SCHEMES.map((value) => ({
            value,
            label: msg(`pensionScheme.${value}`),
          }))}
          onChange={(value) => onChange({ pensionScheme: value as AccountDraft['pensionScheme'] })}
          disabled={disabled}
          hint={draft.pensionScheme === null ? required : undefined}
          error={fieldError('pensionScheme')}
        />
      ) : null}
      {type === 'CUSTOM_ASSET' ? (
        <ChoiceField
          label={msg('addAccount.customAssetType')}
          value={draft.customAssetType}
          options={CUSTOM_ASSET_TYPES.map((value) => ({
            value,
            label: msg(`customAssetType.${value}`),
          }))}
          onChange={(customAssetType) => onChange({ customAssetType })}
          disabled={disabled}
          error={fieldError('customAssetType')}
        />
      ) : null}
      {type === 'CREDIT_CARD' ? (
        <SelectField
          label={msg('addAccount.billingCurrency')}
          value={draft.billingCurrency ?? draft.currency}
          options={currencies}
          onChange={(value) =>
            onChange({ billingCurrency: value === draft.currency ? null : value })
          }
          disabled={disabled}
          hint={msg('addAccount.billingCurrency.hint')}
          error={fieldError('billingCurrency')}
        />
      ) : null}
      {needsPrincipal(type) ? (
        <>
          <AmountField
            label={msg('addAccount.originalPrincipal')}
            currency={draft.currency}
            value={draft.principal}
            editable={!disabled}
            hint={required}
            error={fieldError('originalPrincipal')}
            onChangeValue={(principal, state) =>
              onChange({ principal, principalInvalid: state.invalid })
            }
          />
          <AmountField
            label={msg('addAccount.interestPercent')}
            currency="%"
            value={draft.interest}
            editable={!disabled}
            error={fieldError('interestRatePercent')}
            onChangeValue={(interest, state) =>
              onChange({ interest, interestInvalid: state.invalid })
            }
          />
        </>
      ) : null}
      {needsIdentifier(type) ? (
        <>
          {type === 'SECURITIES' ? (
            <ChoiceField
              label={msg('addAccount.identifier.kind')}
              value={draft.identifierKind}
              options={[
                { value: 'IBAN', label: msg('addAccount.identifier.kind.IBAN') },
                { value: 'CONTRACT_NUMBER', label: msg('addAccount.identifier.contract') },
              ]}
              onChange={(value) =>
                onChange({
                  identifierKind: value as IdentifierKind,
                  identifier: formatIdentifierInput(draft.identifier, value as IdentifierKind),
                })
              }
              disabled={disabled}
            />
          ) : null}
          <IdentifierField
            kind={draft.identifierKind}
            value={draft.identifier}
            editable={!disabled}
            onChange={(identifier) => onChange({ identifier })}
            hint={msg(
              draft.identifierKind === 'MASKED_CARD'
                ? 'addAccount.identifier.optional.card'
                : 'addAccount.identifier.optional',
            )}
            error={
              identifierRejected(draft)
                ? msg('addAccount.identifier.invalid.MASKED_CARD')
                : undefined
            }
          />
        </>
      ) : null}
    </>
  );
}

/**
 * IBAN, card or contract number: grouped while typing; a card number is masked on blur. `error`
 * shows only while the field is not focused, so a half-typed number isn't flagged.
 */
export function IdentifierField({
  kind,
  value,
  onChange,
  editable = true,
  hint,
  error,
}: {
  kind: IdentifierKind;
  value: string;
  onChange: (value: string) => void;
  editable?: boolean;
  hint?: string;
  error?: string;
}) {
  const intl = useIntl();
  const [focused, setFocused] = useState(false);
  return (
    <TextField
      label={intl.formatMessage({ id: identifierLabelKey(kind) })}
      value={value}
      editable={editable}
      onChangeText={(text) => onChange(formatIdentifierInput(text, kind))}
      onFocus={() => setFocused(true)}
      onBlur={() => {
        setFocused(false);
        onChange(identifierOnBlur(value, kind));
      }}
      autoCapitalize="characters"
      autoCorrect={false}
      autoComplete="off"
      importantForAutofill="no"
      placeholder={
        kind === 'MASKED_CARD'
          ? intl.formatMessage({ id: 'addAccount.identifier.card.placeholder' })
          : undefined
      }
      hint={hint}
      error={focused ? undefined : error}
    />
  );
}

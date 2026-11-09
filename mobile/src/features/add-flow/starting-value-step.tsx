import { useRef, useState } from 'react';
import { useIntl } from 'react-intl';
import { Pressable, Text } from 'react-native';

import type { DecimalString } from '@/api/decimal';
import { ActionButton, AmountField, DateField, ErrorBanner } from '@/components/shared';
import { localDateToIso } from '@/components/shared/date-field';
import { TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

import { hasProblemCode, startingStep, validationErrors, type Account } from './model';
import { useRecordOpeningBalance, useRecordValuation } from './use-create';

/**
 * S6 step 2 (M16 style): where the created account's value starts, chosen by its capability
 * flags. "Skip for now" leaves it without a starting value (shown as UNKNOWN, never 0).
 */
export function StartingValueStep({
  account,
  online,
  onDone,
  onImport,
}: {
  account: Account;
  online: boolean;
  onDone: () => void;
  onImport?: (accountId: string) => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const msg = (id: string) => intl.formatMessage({ id });
  const opening = useRecordOpeningBalance();
  const valuation = useRecordValuation();
  const valuationStep = startingStep(account) === 'VALUATION';
  const mutation = valuationStep ? valuation : opening;
  const [amount, setAmount] = useState<DecimalString | null>(null);
  const [amountInvalid, setAmountInvalid] = useState(false);
  const [date, setDate] = useState(() => localDateToIso(new Date()));
  // A second tap can arrive before the pending state re-renders the button (web double-click).
  const busy = useRef(false);
  const errors = validationErrors(mutation.error);
  const fieldError = (...fields: string[]) =>
    fields.some((field) => errors[field]) ? msg('addFlow.fieldError') : undefined;
  const canSave = online && !mutation.isPending && amount !== null && !amountInvalid && !!date;

  async function save() {
    if (!canSave || amount === null || busy.current) return;
    busy.current = true;
    try {
      if (valuationStep) await valuation.mutateAsync({ account, date, value: amount });
      else await opening.mutateAsync({ account, date, balance: amount });
      onDone();
    } catch (error) {
      // A safety net only: the capability flags already route such accounts past this step.
      if (hasProblemCode(error, 'OPENING_BALANCE_NOT_APPLICABLE')) onDone();
      // Otherwise the error shows and the member's draft stays for correction.
    } finally {
      busy.current = false;
    }
  }

  const link = (label: string, onPress: () => void) => (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={{ minHeight: 44, justifyContent: 'center' }}
    >
      <Text style={{ ...TypeScale.labelMedium, color: theme.brandLink }}>{label}</Text>
    </Pressable>
  );

  return (
    <>
      <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
        {msg(valuationStep ? 'addAccount.valuation.help' : 'addAccount.opening.help')}
      </Text>
      <DateField
        label={msg('addAccount.date')}
        value={date}
        onChange={setDate}
        maximumDate={localDateToIso(new Date())}
        error={fieldError('date', 'valuationDate')}
      />
      <AmountField
        label={msg(valuationStep ? 'addAccount.value' : 'addAccount.balance')}
        currency={account.nativeCurrency}
        value={amount}
        error={fieldError('balance', 'value', 'currency')}
        onChangeValue={(next, state) => {
          setAmount(next);
          setAmountInvalid(state.invalid);
        }}
      />
      {account.holdsPositions ? (
        <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
          {msg('addAccount.holdings.note')}
        </Text>
      ) : null}
      {mutation.isError ? <ErrorBanner error={mutation.error} /> : null}
      {!online ? (
        <Text accessibilityRole="alert" style={{ color: theme.statusWarning }}>
          {msg('addFlow.offline')}
        </Text>
      ) : null}
      <ActionButton
        label={msg('addAccount.value.save')}
        disabled={!canSave}
        onPress={() => void save()}
      />
      {!valuationStep && onImport
        ? link(msg('addAccount.importInstead'), () => onImport(account.id))
        : null}
      {link(msg('addFlow.skip'), onDone)}
    </>
  );
}

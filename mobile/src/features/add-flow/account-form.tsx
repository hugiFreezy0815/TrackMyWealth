import { useRef, useState } from 'react';
import { useIntl } from 'react-intl';
import { Pressable, ScrollView, Text } from 'react-native';

import { ActionButton, ErrorBanner, localDateToIso } from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { showToast } from '@/features/shell/toast';
import { useOnline } from '@/hooks/use-online';
import { useTheme } from '@/hooks/use-theme';

import { AccountDetails, IdentifierField } from './account-details';
import {
  accountDraftComplete,
  accountRequest,
  initialAccountDraft,
  typeNameKey,
  withAccountType,
  type AccountDraft,
} from './account-draft';
import {
  identifierErrorKey,
  identifierToSend,
  isNotFound,
  needsIdentifier,
  startingStep,
  validationErrors,
  type Account,
  type AccountType,
  type Portfolio,
} from './model';
import { StartingValueStep } from './starting-value-step';
import { useCreateAccount, useSaveIdentifier } from './use-create';

/** form: step 1; identifier: the account exists but its identifier was rejected; starting: step 2. */
type AccountPhase = 'form' | 'identifier' | 'starting';

type AccountFormProps = {
  portfolio: Portfolio;
  /** The account exists; the flow closes (with the "Account added" toast). */
  onFinished: () => void;
  onCancel: () => void;
  /** The portfolio was removed meanwhile (NOT_FOUND): the route returns to S1 with a toast. */
  onPortfolioGone: () => void;
  onImport?: (accountId: string) => void;
};

/** S6 Add account and its starting value (US-04-11, add-account.md). */
export function AccountForm({
  portfolio,
  onFinished,
  onCancel,
  onPortfolioGone,
  onImport,
}: AccountFormProps) {
  const intl = useIntl();
  const theme = useTheme();
  const online = useOnline();
  const msg = (id: string, values?: Record<string, string | number>) =>
    intl.formatMessage({ id }, values);
  const typeName = (type: AccountType) => msg(typeNameKey(type));
  const [draft, setDraft] = useState<AccountDraft>(() => initialAccountDraft(portfolio, typeName));
  const [phase, setPhase] = useState<AccountPhase>('form');
  const [created, setCreated] = useState<Account | null>(null);
  const create = useCreateAccount();
  const saveIdentifier = useSaveIdentifier();
  // Guards the non-idempotent POST: a second tap can arrive before `isPending` re-renders.
  const busy = useRef(false);
  const inFlight = create.isPending || saveIdentifier.isPending;
  const openingDateValid = draft.openedAt === null || draft.openedAt <= localDateToIso(new Date());
  const canCreate =
    online && !inFlight && created === null && openingDateValid && accountDraftComplete(draft);
  const identifierKey = identifierErrorKey(saveIdentifier.error, draft.identifierKind);

  const patch = (next: Partial<AccountDraft>) => setDraft((current) => ({ ...current, ...next }));

  function finish() {
    showToast('addAccount.added');
    onFinished();
  }

  function advance(account: Account) {
    if (startingStep(account) === 'NONE') finish();
    else setPhase('starting');
  }

  /** Saves the identifier; true when saved. A failure never removes the created account. */
  async function trySaveIdentifier(account: Account): Promise<boolean> {
    const identifier = identifierToSend(draft.identifier, draft.identifierKind);
    if (identifier === null) return true;
    try {
      await saveIdentifier.mutateAsync({ account, identifier, kind: draft.identifierKind });
      return true;
    } catch {
      return false;
    }
  }

  async function guarded(action: () => Promise<void>) {
    if (busy.current) return;
    busy.current = true;
    try {
      await action();
    } finally {
      busy.current = false;
    }
  }

  const submitAccount = () =>
    guarded(async () => {
      if (!canCreate) return;
      let account: Account;
      try {
        account = await create.mutateAsync(accountRequest(portfolio.id, draft));
      } catch (error) {
        if (isNotFound(error)) onPortfolioGone();
        // Otherwise the fields stay for correction; the POST is never replayed automatically.
        return;
      }
      setCreated(account);
      const saved = needsIdentifier(draft.accountType) ? await trySaveIdentifier(account) : true;
      if (saved) advance(account);
      else setPhase('identifier');
    });

  const retryIdentifier = () =>
    guarded(async () => {
      if (!created || !online || inFlight) return;
      if (await trySaveIdentifier(created)) advance(created);
    });

  const step = phase === 'starting' ? 2 : 1;
  return (
    <ScrollView
      keyboardShouldPersistTaps="handled"
      contentContainerStyle={{ gap: Spacing.three, paddingBottom: Spacing.four }}
    >
      <Text style={{ ...TypeScale.labelMedium, color: theme.textSecondary }}>
        {msg('addFlow.step', { current: step, total: 2 })}
      </Text>
      <Text
        accessibilityRole="header"
        style={{ ...TypeScale.headingLarge, color: theme.textPrimary }}
      >
        {/* M17/M16: the screen title says "Add account"; the heading names the portfolio. */}
        {phase === 'starting'
          ? msg('addAccount.start.title')
          : msg('addAccount.portfolio', { name: portfolio.name })}
      </Text>
      {phase === 'starting' ? (
        <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
          {msg('addAccount.portfolio', { name: portfolio.name })}
        </Text>
      ) : null}

      {phase === 'form' ? (
        <>
          <AccountDetails
            draft={draft}
            portfolioCurrency={portfolio.portfolioCurrency}
            disabled={inFlight || created !== null}
            errors={validationErrors(create.error)}
            onChange={(changes) => {
              patch(changes);
              if (create.isError && 'openedAt' in changes) create.reset();
            }}
            onChooseType={(type) => {
              setDraft((current) => withAccountType(current, type, typeName));
              create.reset();
            }}
          />
          {create.isError ? <ErrorBanner error={create.error} /> : null}
          {!online ? (
            <Text accessibilityRole="alert" style={{ color: theme.statusWarning }}>
              {msg('addFlow.offline')}
            </Text>
          ) : null}
          <ActionButton
            label={msg('addAccount.create')}
            disabled={!canCreate}
            onPress={() => void submitAccount()}
          />
          <Pressable
            accessibilityRole="button"
            onPress={onCancel}
            style={{ minHeight: 44, justifyContent: 'center' }}
          >
            <Text style={{ ...TypeScale.labelMedium, color: theme.brandLink }}>
              {msg('addFlow.cancel')}
            </Text>
          </Pressable>
        </>
      ) : null}

      {phase === 'identifier' && created ? (
        <>
          <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
            {msg('addAccount.identifier.failure')}
          </Text>
          <IdentifierField
            kind={draft.identifierKind}
            value={draft.identifier}
            onChange={(identifier) => {
              patch({ identifier });
              saveIdentifier.reset();
            }}
            error={identifierKey ? msg(identifierKey) : undefined}
          />
          {saveIdentifier.isError && !identifierKey ? (
            <ErrorBanner error={saveIdentifier.error} />
          ) : null}
          {!online ? (
            <Text accessibilityRole="alert" style={{ color: theme.statusWarning }}>
              {msg('addFlow.offline')}
            </Text>
          ) : null}
          <ActionButton
            label={msg(`addAccount.identifier.save.${draft.identifierKind}`)}
            disabled={
              !online ||
              inFlight ||
              identifierToSend(draft.identifier, draft.identifierKind) === null
            }
            onPress={() => void retryIdentifier()}
          />
          <Pressable
            accessibilityRole="button"
            onPress={() => advance(created)}
            style={{ minHeight: 44, justifyContent: 'center' }}
          >
            <Text style={{ ...TypeScale.labelMedium, color: theme.brandLink }}>
              {msg('addFlow.skip')}
            </Text>
          </Pressable>
        </>
      ) : null}

      {phase === 'starting' && created ? (
        <StartingValueStep account={created} online={online} onDone={finish} onImport={onImport} />
      ) : null}
    </ScrollView>
  );
}

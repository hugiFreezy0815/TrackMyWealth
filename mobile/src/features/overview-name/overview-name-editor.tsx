import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import { Pressable, Text, View } from 'react-native';

import { typedApi } from '@/api/typed-api';
import { queryKeys } from '@/api/queries/keys';
import { ActionButton, CurrencyPicker, Dialog, ErrorBanner, TextField } from '@/components/shared';
import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

import { scopeCurrencies } from '@/features/overview/overview-model';

import {
  isOverviewNameValidation,
  isOverviewVersionConflict,
  overviewNameLength,
  overviewNameTooLong,
  OVERVIEW_NAME_MAX,
  previewOverviewName,
  submittedOverviewName,
  type Workspace,
} from './model';
import { useUpdateOverviewCurrency, useUpdateOverviewName } from './workspace-mutations';

type Props = {
  workspace: Workspace;
  mode: 'setup' | 'settings';
  onFinished: () => void;
  onSkip?: () => void;
};

export function OverviewNameEditor({ workspace, mode, onFinished, onSkip }: Props) {
  const intl = useIntl();
  const theme = useTheme();
  const client = useQueryClient();
  const rename = useUpdateOverviewName();
  const changeCurrency = useUpdateOverviewCurrency();
  const [draft, setDraft] = useState(workspace.name ?? '');
  const [currency, setCurrency] = useState(workspace.currency);
  const [fieldError, setFieldError] = useState(false);
  const [generalError, setGeneralError] = useState<unknown>(null);
  const [latestName, setLatestName] = useState<string | null | undefined>(undefined);
  const [conflicted, setConflicted] = useState(false);
  // Covers the whole save, including the step between the currency and the name request and the
  // reload after a conflict, when neither mutation is pending.
  const [pending, setPending] = useState(false);
  const label = (id: string, values?: Record<string, number | string>) =>
    intl.formatMessage({ id }, values);
  const invalidLength = overviewNameTooLong(draft);
  const fallback = label('overview.tab.allPortfolios');
  const displayName = previewOverviewName(draft, fallback);

  async function save(forceClear = false): Promise<void> {
    if (pending || (!forceClear && invalidLength)) return;
    setPending(true);
    setFieldError(false);
    setGeneralError(null);
    try {
      let current = client.getQueryData<Workspace>(queryKeys.workspace) ?? workspace;
      // Each request uses the version returned by the preceding request. Never
      // reuse a stale ETag when first-run changes both fields.
      if (mode === 'setup' && currency !== current.currency) {
        current = await changeCurrency.mutateAsync({ workspace: current, currency });
      }
      // An empty field means no name. In Settings that clears a saved name; in first-run setup
      // Continue sends the name only if the member typed one (spec).
      const name = forceClear ? null : submittedOverviewName(draft);
      if (name !== current.name && (mode === 'settings' || name !== null)) {
        current = await rename.mutateAsync({ workspace: current, name });
      }
      onFinished();
    } catch (error) {
      if (isOverviewVersionConflict(error)) {
        // Fetch the other member's latest name, not the stale name in the form.
        // Keeping draft in local state prevents a lost edit while reviewing.
        try {
          const newest = await client.fetchQuery({
            queryKey: queryKeys.workspace,
            queryFn: () => typedApi.get('/api/v1/workspace'),
            staleTime: 0,
          });
          setLatestName(newest.name);
        } catch {
          setLatestName(undefined);
        }
        setConflicted(true);
      } else if (isOverviewNameValidation(error)) {
        setFieldError(true);
      } else {
        setGeneralError(error);
      }
    } finally {
      setPending(false);
    }
  }

  const buttonLabel =
    mode === 'setup' ? label('setup.continue') : label('settings.overviewName.save');
  return (
    <View style={{ gap: Spacing.three }}>
      {mode === 'setup' ? (
        <>
          <Text
            accessibilityRole="header"
            style={{ ...TypeScale.headingLarge, color: theme.textPrimary }}
          >
            {label('setup.overviewName.title')}
          </Text>
          <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
            {label('setup.overviewName.question')}
          </Text>
        </>
      ) : null}
      <TextField
        label={label('setup.overviewName.label')}
        placeholder={fallback}
        value={draft}
        onChangeText={(value) => {
          setDraft(value);
          setFieldError(false);
        }}
        error={
          invalidLength
            ? label('setup.overviewName.tooLong', { max: OVERVIEW_NAME_MAX })
            : fieldError
              ? label('setup.overviewName.invalid')
              : undefined
        }
        hint={label('setup.overviewName.optional')}
        autoCapitalize="sentences"
        testID="overview-name-field"
      />
      <Text
        style={{
          ...TypeScale.caption,
          color: invalidLength ? theme.statusLoss : theme.textSecondary,
        }}
      >
        {label('setup.overviewName.count', {
          count: overviewNameLength(draft),
          max: OVERVIEW_NAME_MAX,
        })}
      </Text>
      <View style={{ gap: Spacing.one }}>
        <Text style={{ ...TypeScale.labelMedium, color: theme.textSecondary }}>
          {label('setup.overviewName.preview')}
        </Text>
        {/* A tab needs a tablist around it (axe aria-required-parent): the preview is a one-tab
            copy of the overview's tab strip, labelled by its caption. */}
        <View accessibilityRole="tablist" accessibilityLabel={label('setup.overviewName.preview')}>
          <View
            accessible
            accessibilityRole="tab"
            aria-selected
            accessibilityLabel={displayName}
            testID="overview-name-preview"
            style={{
              alignSelf: 'flex-start',
              minHeight: 44,
              maxWidth: '100%',
              justifyContent: 'center',
              borderRadius: Radii.pill,
              backgroundColor: theme.bgInverse,
              paddingHorizontal: Spacing.three,
            }}
          >
            <Text numberOfLines={1} style={{ ...TypeScale.labelMedium, color: theme.bgSurface }}>
              {displayName}
            </Text>
          </View>
        </View>
      </View>
      {mode === 'setup' ? (
        <CurrencyPicker
          label={label('setup.currency.label')}
          value={currency}
          onChange={setCurrency}
          disabled={pending}
          options={scopeCurrencies(workspace.currency).map((value) => ({ value, label: value }))}
        />
      ) : null}
      {generalError ? <ErrorBanner error={generalError} /> : null}
      <View style={{ gap: Spacing.two }}>
        <ActionButton
          label={buttonLabel}
          disabled={pending || invalidLength}
          onPress={() => void save()}
        />
        {mode === 'settings' && workspace.name !== null ? (
          <Pressable
            accessibilityRole="button"
            aria-disabled={pending}
            disabled={pending}
            onPress={() => void save(true)}
            style={{ minHeight: 44, justifyContent: 'center', alignItems: 'center' }}
          >
            <Text style={{ ...TypeScale.labelMedium, color: theme.brandLink }}>
              {label('settings.overviewName.clear')}
            </Text>
          </Pressable>
        ) : null}
        {mode === 'setup' && onSkip ? (
          <Pressable
            accessibilityRole="button"
            aria-disabled={pending}
            disabled={pending}
            onPress={onSkip}
            style={{ minHeight: 44, justifyContent: 'center', alignItems: 'center' }}
          >
            <Text style={{ ...TypeScale.labelMedium, color: theme.brandLink }}>
              {label('setup.skip')}
            </Text>
          </Pressable>
        ) : null}
      </View>
      <Dialog
        visible={conflicted}
        title={label('conflict.title')}
        onClose={() => setConflicted(false)}
      >
        <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
          {label('setup.overviewName.conflict.theirs', {
            name: latestName ?? fallback,
          })}
        </Text>
        <Text style={{ ...TypeScale.bodyMedium, color: theme.textPrimary }}>
          {label('setup.overviewName.conflict.yours', { name: displayName })}
        </Text>
        <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
          {label('setup.overviewName.conflict.help')}
        </Text>
        <ActionButton label={label('conflict.review')} onPress={() => setConflicted(false)} />
      </Dialog>
    </View>
  );
}

import { useState } from 'react';
import { Button, Pressable, StyleSheet, TextInput, View } from 'react-native';

import { ThemedText } from '@/components/themed-text';

import {
  ACCOUNT_TYPES,
  type AccountProposal,
  type ConfirmedAccountDraft,
  prepareAccountDraft,
} from './account-proposal';

/** One of the workspace's institutions, as the member chose it. */
export type ChosenInstitution = { id: string; name: string };

type Props = {
  proposal: AccountProposal;
  /** Chosen by the member among the workspace's institutions; null until then. */
  institution: ChosenInstitution | null;
  onConfirm: (draft: ConfirmedAccountDraft) => void;
  onCancel: () => void;
};

/**
 * US-07-11: the member reviews what a statement suggests for a new account. Creates nothing: the
 * parent submits the confirmed draft to `POST /api/v1/accounts`, then registers the file's
 * identifier on the new account.
 */
export function AccountProposalReview({ proposal, institution, onConfirm, onCancel }: Props) {
  const [name, setName] = useState('');
  const [accountType, setAccountType] = useState(proposal.suggestedAccountType ?? '');
  const [currency, setCurrency] = useState(proposal.suggestedCurrency ?? '');
  const [error, setError] = useState<string | null>(null);

  function confirm() {
    try {
      const draft = prepareAccountDraft(institution?.id ?? '', name, accountType, currency);
      setError(null);
      onConfirm(draft);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Please confirm the account details.');
    }
  }

  return (
    <View style={styles.form}>
      <ThemedText type="subtitle">Review new account</ThemedText>
      <ThemedText type="small">
        Suggestions come from the statement. Nothing is created until you confirm.
      </ThemedText>
      <ThemedText>
        Bank named by the statement: {proposal.institutionName ?? 'Not named'}
      </ThemedText>
      <ThemedText>Institution: {institution?.name ?? 'Choose an institution first'}</ThemedText>
      <ThemedText>Account identifier: {proposal.maskedIdentifier ?? 'Not found'}</ThemedText>
      <ThemedText>Account name</ThemedText>
      <TextInput
        accessibilityLabel="Account name"
        value={name}
        onChangeText={setName}
        placeholder="Account name"
        style={styles.input}
      />
      <ThemedText>Account type</ThemedText>
      <View accessibilityRole="radiogroup" accessibilityLabel="Account type" style={styles.types}>
        {ACCOUNT_TYPES.map((type) => (
          <Pressable
            key={type}
            accessibilityRole="radio"
            accessibilityState={{ selected: type === accountType }}
            onPress={() => setAccountType(type)}
            style={[styles.type, type === accountType && styles.selected]}
          >
            <ThemedText>{type}</ThemedText>
          </Pressable>
        ))}
      </View>
      <ThemedText>Currency (ISO 4217)</ThemedText>
      <TextInput
        accessibilityLabel="Currency"
        value={currency}
        onChangeText={setCurrency}
        autoCapitalize="characters"
        maxLength={3}
        style={styles.input}
      />
      {error && <ThemedText accessibilityRole="alert">{error}</ThemedText>}
      <Button title="Confirm new account details" onPress={confirm} />
      <Button title="Cancel" onPress={onCancel} />
    </View>
  );
}

const styles = StyleSheet.create({
  form: { padding: 16, gap: 12 },
  input: { borderWidth: 1, borderRadius: 6, padding: 10 },
  types: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  type: { borderWidth: 1, borderRadius: 6, paddingHorizontal: 10, paddingVertical: 6 },
  selected: { borderWidth: 2 },
});

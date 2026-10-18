import { useEffect, useState } from 'react';
import { Button, ScrollView, StyleSheet, View } from 'react-native';

import { ApiError } from '@/api/client';
import { ThemedText } from '@/components/themed-text';
import { ThemedView } from '@/components/themed-view';
import { BottomTabInset, MaxContentWidth, Spacing } from '@/constants/theme';
import { type Identity, isAdministrator } from '@/features/auth/session';

import { loadReferenceData, type ReferenceDataResponse } from './reference-data';

type Props = { identity: Identity | null };

type State =
  | { status: 'loading' }
  | { status: 'loaded'; data: ReferenceDataResponse }
  | { status: 'not-available' }
  | { status: 'error' };

const COUNT_LABELS: { key: keyof ReferenceDataResponse['contents']; label: string }[] = [
  { key: 'catalogueInstitutions', label: 'Catalogue institutions' },
  { key: 'defaultCategories', label: 'Default categories' },
  { key: 'sourceCodeMappings', label: 'Source-code mappings' },
  { key: 'fallbackSectors', label: 'Fallback sectors' },
];

function friendlyDateTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? 'Not available' : date.toLocaleString();
}

/** A calendar date (LocalDate) - read as UTC midnight so no time zone moves it to another day. */
export function friendlyDate(isoDate: string): string {
  const date = new Date(`${isoDate}T00:00:00Z`);
  return Number.isNaN(date.getTime())
    ? 'Not available'
    : date.toLocaleDateString(undefined, { timeZone: 'UTC' });
}

/**
 * US-01-05: show reference-data health only to the server-verified administrator.
 * Visibility is not authorization: the backend enforces the role again on each request.
 */
export function ReferenceDataScreen({ identity }: Props) {
  const administrator = isAdministrator(identity);
  const [retry, setRetry] = useState(0);
  const [state, setState] = useState<State>({ status: 'loading' });

  useEffect(() => {
    if (!administrator) return;

    let active = true;
    void loadReferenceData()
      .then((data) => {
        if (active) setState({ status: 'loaded', data });
      })
      .catch((error: unknown) => {
        if (!active) return;
        // Never expose the server's 403 response body or internal error information.
        const forbidden = error instanceof ApiError && error.status === 403;
        setState({ status: forbidden ? 'not-available' : 'error' });
      });

    return () => {
      active = false;
      // Drop loaded data too, so a session that loses the role keeps nothing of it in memory.
      setState({ status: 'loading' });
    };
  }, [administrator, retry]);

  // A deep link must not fetch or render admin data without a verified administrator identity.
  if (!administrator) {
    return (
      <View style={styles.center} accessibilityRole="alert">
        <ThemedText>Reference data is not available.</ThemedText>
      </View>
    );
  }

  return (
    <ScrollView contentContainerStyle={styles.scrollContent}>
      <View style={styles.content}>
        <ThemedText type="subtitle">Reference data</ThemedText>
        <ThemedText type="small" themeColor="textSecondary">
          Installed package and reference-data health
        </ThemedText>

        {state.status === 'loading' && (
          <ThemedText accessibilityRole="text">Loading reference data…</ThemedText>
        )}

        {state.status === 'not-available' && (
          <ThemedText accessibilityRole="alert">Reference data is not available.</ThemedText>
        )}

        {state.status === 'error' && (
          <ThemedView type="backgroundElement" style={styles.section}>
            <ThemedText accessibilityRole="alert">
              Could not load reference data. Check your connection and try again.
            </ThemedText>
            <Button title="Retry" onPress={() => setRetry((value) => value + 1)} />
          </ThemedView>
        )}

        {state.status === 'loaded' && (
          <>
            <ThemedView type="backgroundElement" style={styles.section}>
              <ThemedText type="smallBold">Current package</ThemedText>
              <ThemedText>Version: {state.data.packageVersion}</ThemedText>
              <ThemedText>Published: {friendlyDate(state.data.publicationDate)}</ThemedText>
              <ThemedText>Imported: {friendlyDateTime(state.data.importedAt)}</ThemedText>
              <ThemedText>
                Imported by: {state.data.importedBy ?? 'Application baseline'}
              </ThemedText>
            </ThemedView>

            <ThemedView type="backgroundElement" style={styles.section}>
              <ThemedText type="smallBold">Content counts</ThemedText>
              {COUNT_LABELS.map(({ key, label }) => (
                <ThemedText key={key}>
                  {label}: {state.data.contents[key]}
                </ThemedText>
              ))}
              <ThemedText>
                GICS structure: {state.data.contents.gicsStructureVersion ?? 'Not loaded'}
              </ThemedText>
            </ThemedView>

            <ThemedView type="backgroundElement" style={styles.section}>
              <ThemedText type="smallBold">Staleness warnings</ThemedText>
              {state.data.stalenessWarnings.length === 0 ? (
                <ThemedText>No staleness warnings.</ThemedText>
              ) : (
                state.data.stalenessWarnings.map((warning) => (
                  <ThemedText
                    key={`${warning.code}-${warning.subject}-${warning.missingPeriod}`}
                    accessibilityRole="alert"
                  >
                    {warning.code.replaceAll('_', ' ')}: {warning.subject} ({warning.missingPeriod})
                  </ThemedText>
                ))
              )}
            </ThemedView>
          </>
        )}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scrollContent: {
    alignItems: 'center',
    paddingHorizontal: Spacing.three,
    paddingVertical: Spacing.four,
    paddingBottom: BottomTabInset + Spacing.four,
  },
  content: {
    width: '100%',
    maxWidth: MaxContentWidth,
    gap: Spacing.three,
  },
  section: {
    borderRadius: Spacing.three,
    padding: Spacing.three,
    gap: Spacing.two,
  },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: Spacing.four,
  },
});

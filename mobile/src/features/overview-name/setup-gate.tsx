import { useEffect, useState, type ReactNode } from 'react';
import { View } from 'react-native';

import { usePortfolios, useWorkspace } from '@/api/queries/resources';
import { LoadingSkeleton } from '@/components/shared';
import { Spacing } from '@/constants/theme';
import { useOnline } from '@/hooks/use-online';

import { completeOverviewStep, hasCompletedOverviewStep, setupStepKey } from './device-step';
import { isFirstOverviewSetup } from './model';
import { OverviewNameEditor } from './overview-name-editor';

/** What the signed-in content area shows while it cannot decide yet what to show. */
export function ContentLoading() {
  return (
    <View style={{ padding: Spacing.four }}>
      <LoadingSkeleton count={3} height={36} />
    </View>
  );
}

/** First-run step after sign-in/MFA, before financial routes are shown to a new member. */
export function OverviewSetupGate({ userId, children }: { userId: string; children: ReactNode }) {
  const workspace = useWorkspace();
  const portfolios = usePortfolios();
  const online = useOnline();
  const [record, setRecord] = useState<{ key: string; complete: boolean } | null>(null);
  const workspaceId = workspace.data?.id;
  const key = workspaceId ? setupStepKey(userId, workspaceId) : null;

  useEffect(() => {
    if (!workspaceId) return;
    const id = workspaceId;
    const currentKey = setupStepKey(userId, id);
    let mounted = true;
    void hasCompletedOverviewStep(userId, id).then((complete) => {
      if (mounted) setRecord({ key: currentKey, complete });
    });
    return () => {
      mounted = false;
    };
  }, [userId, workspaceId]);

  // Do not block an existing member when offline or an API is unavailable.
  // The ordinary screen owns its own per-card error and offline states.
  if (!online || workspace.isError || portfolios.isError) return <>{children}</>;
  if (workspace.isPending || portfolios.isPending || !key || record?.key !== key) {
    return <ContentLoading />;
  }

  if (!isFirstOverviewSetup(portfolios.data ?? [], record.complete)) return <>{children}</>;

  const finish = () => {
    // Update immediately even if device storage is inaccessible.
    setRecord({ key, complete: true });
    void completeOverviewStep(userId, workspace.data.id);
  };
  return (
    <View style={{ padding: Spacing.four, flex: 1 }}>
      <OverviewNameEditor
        mode="setup"
        workspace={workspace.data}
        onFinished={finish}
        onSkip={finish}
      />
    </View>
  );
}

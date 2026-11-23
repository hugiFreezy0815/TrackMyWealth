import { setStringAsync } from 'expo-clipboard';

import { showToast } from '@/features/shell/toast';

export async function copyTransactionPurpose(purpose: string): Promise<void> {
  let copied = false;
  try {
    copied = await setStringAsync(purpose, {
      android: { isSensitive: true },
      ios: { localOnly: true },
    });
  } catch {
    showToast('account.purposeCopyFailed');
    return;
  }
  showToast(copied ? 'account.purposeCopied' : 'account.purposeCopyFailed');
}

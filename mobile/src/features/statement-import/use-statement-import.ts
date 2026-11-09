import { useEffect, useRef, useState } from 'react';
import * as DocumentPicker from 'expo-document-picker';

import { useAccount } from '@/api/queries/resources';
import { useOnline } from '@/hooks/use-online';

import {
  commitStatement,
  confirmStatementAccount,
  discardStatement,
  downloadErrorsCsv,
  parseStatement,
  setStatementRowIncluded,
  uploadStatement,
  useImportBatch,
  useImportCache,
  useOpenBatches,
} from './api';
import { deletePickedCopy, shareErrorsCsv } from './device-files';
import {
  canCommit,
  importProblemCode,
  isOpenBatch,
  isRetryableUploadError,
  retryAllowedAt,
  statementFileError,
  type Batch,
  type ImportRow,
  type RowFilter,
  type StatementFile,
} from './model';

/** S7 "Uploading / parsing": after this long, a scanned PDF's OCR is the likely reason. */
export const SLOW_UPLOAD_MS = 10_000;

/** The system picker's types; the extension check in `statementFileError` decides. */
const PICKER_TYPES = [
  'text/csv',
  'text/plain',
  'text/xml',
  'application/xml',
  'application/pdf',
  'application/vnd.ms-excel',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/zip',
  // CSV and CAMT files often arrive without a usable MIME type (e.g. from mail attachments).
  'application/octet-stream',
];

export const ERRORS_FILE_NAME = 'import-errors.csv';

type Options = {
  fixedAccountId?: string;
  initialBatchId?: string;
  onExit: () => void;
};

type Recovery = (failure: unknown) => Promise<void>;

/**
 * S7's state and its writes. One batch, held in its query cache entry (the authoritative copy,
 * with the version every If-Match sends), and one write at a time: a second tap while a write
 * runs is ignored, and the controls show busy.
 */
export function useStatementImport({ fixedAccountId, initialBatchId, onExit }: Options) {
  const online = useOnline();
  const [selectedAccountId, setSelectedAccountId] = useState(fixedAccountId ?? '');
  const accountId = fixedAccountId ?? selectedAccountId;
  const [batchId, setBatchId] = useState(initialBatchId ?? '');
  const [file, setFile] = useState<StatementFile | null>(null);
  const [fileError, setFileError] = useState<ReturnType<typeof statementFileError>>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [slowUpload, setSlowUpload] = useState(false);
  const [retryAt, setRetryAt] = useState<number | null>(null);
  const [filter, setFilter] = useState<RowFilter>('ALL');
  const [selectedTemplate, setSelectedTemplate] = useState('');
  const [dialog, setDialog] = useState<'discard' | 'conflict' | null>(null);
  const [showOpen, setShowOpen] = useState(false);
  const lock = useRef(false);
  const uploadAbort = useRef<AbortController | null>(null);
  // The picker's cache copy of a statement, kept only while a Retry may still need it.
  const pickedCopy = useRef<string | null>(null);

  const account = useAccount(accountId);
  const loaded = useImportBatch(accountId, batchId);
  const batch = batchId ? (loaded.data ?? null) : null;
  const openBatches = useOpenBatches(accountId, showOpen);
  const cache = useImportCache(accountId);
  const canUpload = online && !busy && account.data?.hasTransactions === true;

  useEffect(() => {
    if (!uploading) return;
    const timer = setTimeout(() => setSlowUpload(true), SLOW_UPLOAD_MS);
    return () => {
      clearTimeout(timer);
      setSlowUpload(false);
    };
  }, [uploading]);

  // Leaving the screen mid-upload abandons the request and never leaves the statement behind.
  useEffect(
    () => () => {
      uploadAbort.current?.abort();
      if (pickedCopy.current) deletePickedCopy(pickedCopy.current);
    },
    [],
  );

  function releasePickedCopy() {
    if (pickedCopy.current) deletePickedCopy(pickedCopy.current);
    pickedCopy.current = null;
  }

  function show(next: Batch) {
    cache.store(next);
    if (next.id !== batchId) {
      setBatchId(next.id);
      setFilter('ALL');
      setSelectedTemplate(next.templateCandidates[0]?.template.id ?? '');
    }
  }

  /** The reactions of S7 to a failed write on the batch on screen. */
  const recoverBatch: Recovery = async (failure) => {
    const code = importProblemCode(failure);
    if (code === 'VERSION_CONFLICT' && batchId) {
      setDialog('conflict');
      await cache.reload(batchId).catch(() => undefined);
    } else if (code === 'IMPORT_BATCH_STATE' && batchId) {
      // Already committed or discarded elsewhere: a commit that went through shows step 3.
      const current = await cache.reload(batchId).catch(() => null);
      if (current?.status === 'COMMITTED') {
        setError(null);
        await cache.committed();
      }
    } else if (code === 'IMPORT_TOO_MANY_OPEN_BATCHES') {
      setShowOpen(true);
    }
  };

  async function write(
    action: () => Promise<Batch>,
    after: (next: Batch) => Promise<void> | void,
    recover: Recovery = recoverBatch,
  ): Promise<void> {
    if (lock.current || !online) return;
    lock.current = true;
    setBusy(true);
    setError(null);
    try {
      await after(await action());
    } catch (failure) {
      setError(failure);
      await recover(failure);
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }

  async function upload(chosen: StatementFile) {
    if (pickedCopy.current !== chosen.uri) releasePickedCopy();
    pickedCopy.current = chosen.webFile ? null : chosen.uri;
    setFile(chosen);
    setRetryAt(null);
    setError(null);
    const problem = statementFileError(chosen);
    setFileError(problem);
    if (problem || !accountId || !canUpload) {
      releasePickedCopy();
      return;
    }
    const abort = new AbortController();
    uploadAbort.current = abort;
    setUploading(true);
    let keepForRetry = false;
    await write(
      () => uploadStatement(accountId, chosen, abort.signal),
      (next) => {
        show(next);
        setFile(null);
      },
      async (failure) => {
        if (abort.signal.aborted) {
          // Cancelled by the member: nothing to report.
          setError(null);
          setFile(null);
          return;
        }
        keepForRetry = isRetryableUploadError(failure);
        setRetryAt(retryAllowedAt(failure));
        await recoverBatch(failure);
      },
    );
    uploadAbort.current = null;
    setUploading(false);
    if (!keepForRetry) releasePickedCopy();
  }

  async function chooseFile() {
    if (!canUpload || !accountId) return;
    try {
      const result = await DocumentPicker.getDocumentAsync({
        type: PICKER_TYPES,
        copyToCacheDirectory: true,
        multiple: false,
      });
      const asset = result.canceled ? undefined : result.assets[0];
      if (!asset) return;
      await upload({
        name: asset.name,
        uri: asset.uri,
        size: asset.size ?? null,
        mimeType: asset.mimeType,
        webFile: asset.file,
      });
    } catch (failure) {
      setError(failure);
    }
  }

  function retryUpload() {
    if (file && (retryAt === null || Date.now() >= retryAt)) void upload(file);
  }

  function cancelUpload() {
    uploadAbort.current?.abort();
  }

  function parse() {
    if (!batch) return;
    const templateId = selectedTemplate || batch.templateCandidates[0]?.template.id;
    if (!templateId) return;
    void write(() => parseStatement(accountId, batch, templateId), show);
  }

  function confirmAccount() {
    if (!batch) return;
    void write(
      () => confirmStatementAccount(accountId, batch),
      (next) => cache.afterWrite(batch, next),
    );
  }

  function switchRow(row: ImportRow, included: boolean) {
    if (!batch) return;
    void write(
      () => setStatementRowIncluded(accountId, batch, row.rowNumber, included),
      (next) => cache.afterWrite(batch, next, { rowNumber: row.rowNumber, included }),
    );
  }

  function commit(unknownRow: boolean) {
    if (!batch || unknownRow || !canCommit(batch, busy, online)) return;
    void write(
      () => commitStatement(accountId, batch),
      async (next) => {
        cache.store(next);
        if (next.status === 'COMMITTED') await cache.committed();
      },
    );
  }

  function startAgain() {
    setBatchId('');
    setFile(null);
    setFileError(null);
    setError(null);
    setRetryAt(null);
    setFilter('ALL');
  }

  async function discard() {
    setDialog(null);
    if (!batch || !isOpenBatch(batch)) {
      onExit();
      return;
    }
    await write(
      () => discardStatement(accountId, batch),
      async (next) => {
        cache.store(next);
        await cache.discarded();
        if (next.status === 'DISCARDED') {
          startAgain();
          onExit();
        }
      },
    );
  }

  /** A batch from the open-batches list, which is not the one on screen. */
  function discardOpen(item: Batch) {
    void write(
      () => discardStatement(accountId, item),
      () => cache.discarded(),
      async (failure) => {
        if (importProblemCode(failure) === 'IMPORT_TOO_MANY_OPEN_BATCHES') setShowOpen(true);
        await openBatches.refetch();
      },
    );
  }

  function resume(item: Batch) {
    show(item);
    setShowOpen(false);
    setError(null);
  }

  async function downloadErrors(title: string) {
    if (!batch) return;
    try {
      await shareErrorsCsv(await downloadErrorsCsv(accountId, batch.id), ERRORS_FILE_NAME, title);
    } catch (failure) {
      setError(failure);
    }
  }

  return {
    online,
    accountId,
    fixedAccount: fixedAccountId !== undefined,
    account,
    batchId,
    batch,
    loaded,
    openBatches,
    file,
    fileError,
    error,
    busy,
    uploading,
    slowUpload,
    retryAt,
    canUpload,
    filter,
    selectedTemplate,
    dialog,
    showOpen,
    selectAccount(id: string) {
      setSelectedAccountId(id);
      setError(null);
      setShowOpen(false);
    },
    setFilter,
    selectTemplate: setSelectedTemplate,
    toggleOpenBatches: () => setShowOpen((value) => !value),
    askDiscard: () => setDialog('discard'),
    closeDialog: () => setDialog(null),
    reviewConflict() {
      setDialog(null);
      if (batchId) void cache.reload(batchId).catch(() => undefined);
    },
    chooseFile,
    retryUpload,
    cancelUpload,
    parse,
    confirmAccount,
    switchRow,
    commit,
    discard,
    discardOpen,
    resume,
    startAgain,
    downloadErrors,
  };
}

export type StatementImport = ReturnType<typeof useStatementImport>;

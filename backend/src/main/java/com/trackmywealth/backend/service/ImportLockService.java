package com.trackmywealth.backend.service;

import com.trackmywealth.backend.repository.WorkspaceRepository;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The per-account import lock (#287): a transaction-scoped advisory lock that an import commit
 * ({@link ImportCommitService}) and a rollback ({@link ImportRollbackService}) take first, and a
 * removal, correction or restore ({@link TransactionRemovalService}) before the cards. While a
 * commit holds it, the rows its preview found duplicate keep the ledger rows it checked them
 * against. Accounts are always locked in id order, so two writers locking several never deadlock.
 */
@Service
public class ImportLockService {

  private static final String LOCK_PREFIX = "import-commit:";

  private final WorkspaceRepository workspaceRepository;

  public ImportLockService(WorkspaceRepository workspaceRepository) {
    this.workspaceRepository = workspaceRepository;
  }

  /** Waits for the account's import lock; it is released when the current transaction ends. */
  public void lockAccount(UUID accountId) {
    workspaceRepository.lockAdvisory(LOCK_PREFIX + accountId);
  }

  /** As {@link #lockAccount}, for each of {@code accountIds} in id order. */
  public void lockAccounts(Collection<UUID> accountIds) {
    accountIds.stream().distinct().sorted().forEach(this::lockAccount);
  }
}

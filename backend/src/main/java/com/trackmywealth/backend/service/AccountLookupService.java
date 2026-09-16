package com.trackmywealth.backend.service;

import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.repository.AccountRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared "look up this account or 404" guard (first extracted from {@code AccountService}, {@code
 * CustomAssetValuationService}, and {@code SharingGrantService}, which had each independently
 * reimplemented the same lookup - the same "extract the duplicated check" pattern {@link
 * WorkspaceAccessService} already used). RLS already confines the underlying {@code SELECT} to the
 * caller's own workspace, so a cross-workspace id simply isn't found, degrading safely to 404
 * rather than needing a separate equality check.
 */
@Service
public class AccountLookupService {

  private final AccountRepository accountRepository;

  public AccountLookupService(AccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  public Account findAccountOrThrow(UUID accountId) {
    return accountRepository
        .findById(accountId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));
  }
}

package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import java.util.UUID;

/**
 * Shared-primary-key mapping common to every {@link Account} extension table (V5, V23) - each one's
 * primary key is also its foreign key to {@code account(id)} (DB-09/DB-10), which structurally
 * enforces "at most one extension row per account." Every extension entity (e.g. {@link
 * AccountCreditCard}, {@link AccountMortgage}) extends this for the {@code accountId}/ {@code
 * account} mapping and adds only its own type-specific columns.
 */
@MappedSuperclass
public class AccountExtension {

  @Id
  @Column(name = "account_id")
  private UUID accountId;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId
  @JoinColumn(name = "account_id")
  private Account account;

  public UUID getAccountId() {
    return accountId;
  }

  public Account getAccount() {
    return account;
  }

  public void setAccount(Account account) {
    this.account = account;
  }
}

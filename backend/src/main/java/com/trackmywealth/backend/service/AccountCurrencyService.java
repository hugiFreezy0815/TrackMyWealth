package com.trackmywealth.backend.service;

import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountCreditCard;
import com.trackmywealth.backend.repository.AccountCreditCardRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The currency an account's own figures are denominated in: its value ({@link
 * AccountValuationService}), its snapshots and its opening balance (US-25-01, US-25-04). V58's
 * snapshot currency guard enforces the same rule in the database.
 */
@Service
public class AccountCurrencyService {

  private final AccountCreditCardRepository accountCreditCardRepository;

  public AccountCurrencyService(AccountCreditCardRepository accountCreditCardRepository) {
    this.accountCreditCardRepository = accountCreditCardRepository;
  }

  /**
   * {@code account.nativeCurrency} for everything except a {@code CREDIT_CARD}, where it is the
   * card's {@code billing_currency} instead (US-09-04/FR-CC-010): {@code CreateAccountRequest} lets
   * the two legitimately differ, and {@code TransactionRepository}'s balance queries sum a
   * foreign-currency purchase's {@code amount} converted to {@code billing_currency} (via {@code
   * fxRateToAccountCurrency}) - never to {@code nativeCurrency}. Treating {@code nativeCurrency} as
   * the ledger's own currency whenever the two diverge would silently mislabel (and, for a
   * same-currency fast path, under-convert) the resulting balance.
   */
  @Transactional(readOnly = true)
  public String ownCurrency(Account account) {
    if (!account.isHasStatementCycle()) {
      return account.getNativeCurrency();
    }
    return accountCreditCardRepository
        .findById(account.getId())
        .map(AccountCreditCard::getBillingCurrency)
        // Defensive only: trg_extension_type_guard (V5) means a CREDIT_CARD account always has
        // this row in practice.
        .orElseGet(account::getNativeCurrency);
  }

  /**
   * #380: {@link #ownCurrency} for several accounts, reading the cards' billing currencies in one
   * query rather than one per card.
   */
  @Transactional(readOnly = true)
  public Map<UUID, String> ownCurrencies(Collection<Account> accounts) {
    Map<UUID, String> currencies = new HashMap<>();
    for (Account account : accounts) {
      currencies.put(account.getId(), account.getNativeCurrency());
    }
    List<UUID> cards =
        accounts.stream().filter(Account::isHasStatementCycle).map(Account::getId).toList();
    if (!cards.isEmpty()) {
      for (AccountCreditCard card : accountCreditCardRepository.findAllById(cards)) {
        currencies.put(card.getAccountId(), card.getBillingCurrency());
      }
    }
    return currencies;
  }
}

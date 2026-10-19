package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportRowPair;
import com.trackmywealth.backend.dto.ImportTransferPlan;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-07-22 (#325): links the transfers an import's own file books, so that neither counts as income
 * or spending, before transfer matching ({@link TransferDetectionService}) could pair a leg with
 * something else:
 *
 * <ul>
 *   <li><b>Currency exchange</b> - two rows of the batch sharing the bank's group reference ({@code
 *       groupReference}, e.g. Yuh's REFERENZ) and booking date, one sending and one receiving money
 *       in different currencies, are linked as a pair the system decided. The legs keep the types
 *       the template gave them: a {@code TRANSFER} must be in its account's currency, and at least
 *       one leg of an exchange is not; once linked, cash flow leaves both out whatever their type.
 *       A reference shared by any other number of rows, or by rows of one currency, pairs nothing:
 *       the rows are left to matching and the member.
 *   <li><b>Savings pocket move</b> - a row of the template's {@code savingsPocketPattern} is linked
 *       to a new leg on the account's savings pocket ({@link SavingsPocketService}) with the
 *       opposite amount, like a manual two-sided transfer; money moved into the pocket then counts
 *       as saving. Without a usable pocket the row stays a one-sided transfer, pending review.
 * </ul>
 *
 * <p>Every link is made in the commit's transaction and undone by a rollback of the batch: the
 * pocket legs are rows of the batch, and the exchange pairs are decided by the system.
 */
@Service
public class ImportTransferLinkService {

  // An exchange has exactly two legs: the money sent and the money received.
  private static final int EXCHANGE_LEGS = 2;

  private final TransactionRepository transactionRepository;
  private final AccountRepository accountRepository;
  private final TransferDetectionService transferDetectionService;
  private final TransferRecordingService transferRecordingService;
  private final ReconciliationService reconciliationService;

  public ImportTransferLinkService(
      TransactionRepository transactionRepository,
      AccountRepository accountRepository,
      TransferDetectionService transferDetectionService,
      TransferRecordingService transferRecordingService,
      ReconciliationService reconciliationService) {
    this.transactionRepository = transactionRepository;
    this.accountRepository = accountRepository;
    this.transferDetectionService = transferDetectionService;
    this.transferRecordingService = transferRecordingService;
    this.reconciliationService = reconciliationService;
  }

  /**
   * What recording {@code rows}, in this order, into {@code account} links; the pocket moves' other
   * legs go to {@code savingsPocketAccountId} ({@code null}: none). A card or loan links nothing: a
   * card settles and a loan is repaid instead.
   */
  public static ImportTransferPlan plan(
      Account account, List<CanonicalImportRow> rows, UUID savingsPocketAccountId) {
    if (account.isHasStatementCycle() || account.isHasAmortisation()) {
      return new ImportTransferPlan(List.of(), List.of(), null);
    }
    List<Integer> pocketMoves = new ArrayList<>();
    Map<String, List<Integer>> legsByGroup = new LinkedHashMap<>();
    for (int i = 0; i < rows.size(); i++) {
      CanonicalImportRow row = rows.get(i);
      if (row.savingsPocket()) {
        pocketMoves.add(i);
      } else if (row.groupReference() != null && isLeg(row)) {
        legsByGroup
            .computeIfAbsent(row.groupReference() + '|' + row.bookingDate(), k -> new ArrayList<>())
            .add(i);
      }
    }
    List<ImportRowPair> exchanges = new ArrayList<>();
    for (List<Integer> legs : legsByGroup.values()) {
      if (legs.size() == EXCHANGE_LEGS) {
        CanonicalImportRow first = rows.get(legs.get(0));
        CanonicalImportRow second = rows.get(legs.get(1));
        boolean firstSends = first.amount().signum() < 0;
        if (firstSends != (second.amount().signum() < 0)
            && !first.currency().equals(second.currency())) {
          exchanges.add(
              firstSends
                  ? new ImportRowPair(legs.get(0), legs.get(1))
                  : new ImportRowPair(legs.get(1), legs.get(0)));
        }
      }
    }
    return new ImportTransferPlan(exchanges, pocketMoves, savingsPocketAccountId);
  }

  /**
   * Applies {@code plan} to {@code recorded}, the rows just recorded into {@code account} by import
   * batch {@code importBatchId}, in the order planned. Runs inside the commit, before transfer
   * matching.
   */
  @Transactional
  public void link(
      Account account,
      ImportTransferPlan plan,
      List<Transaction> recorded,
      UUID importBatchId,
      AuthenticatedUserPrincipal actor) {
    if (plan.isEmpty()) {
      return;
    }
    transferDetectionService.lockForWrite(account);
    // The recorded rows were detached after their chunk was flushed; link the managed ones.
    Map<UUID, Transaction> managed = new HashMap<>();
    transactionRepository
        .findAllById(recorded.stream().map(Transaction::getId).toList())
        .forEach(row -> managed.put(row.getId(), row));

    for (ImportRowPair exchange : plan.exchanges()) {
      transferDetectionService.confirmImportedPair(
          managed.get(recorded.get(exchange.debit()).getId()),
          managed.get(recorded.get(exchange.credit()).getId()));
    }

    if (plan.savingsPocketAccountId() != null && !plan.pocketMoves().isEmpty()) {
      // SavingsPocketService resolved it in this transaction: it exists and may be written to.
      Account pocket = accountRepository.findById(plan.savingsPocketAccountId()).orElseThrow();
      LocalDate earliest = null;
      for (int position : plan.pocketMoves()) {
        Transaction move = managed.get(recorded.get(position).getId());
        move.setInternalTransfer(true);
        move.setCounterpartyAccountId(pocket.getId());
        transferRecordingService.recordPocketLeg(
            transactionRepository.saveAndFlush(move), pocket, importBatchId, actor);
        if (earliest == null || move.getBookingDate().isBefore(earliest)) {
          earliest = move.getBookingDate();
        }
      }
      reconciliationService.reconcileAfterLedgerChange(pocket, earliest, actor.userId());
    }
    transactionRepository.flush();
  }

  // A leg transfer matching could pair too (TransferDetectionService): money moved out, or in.
  private static boolean isLeg(CanonicalImportRow row) {
    if (row.amount().signum() < 0) {
      return TransferDetectionService.DEBIT_TYPES.contains(row.transactionType());
    }
    return row.amount().signum() > 0
        && TransferDetectionService.CREDIT_TYPES.contains(row.transactionType());
  }
}

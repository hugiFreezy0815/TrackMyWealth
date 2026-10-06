package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.ImportPdfBookingLine;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.dto.ImportTemplateValues;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.error.ImportRowRejectedException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * #268: the running balance check of a PDF statement whose layout names a balance column: each
 * booking must lead from the balance before it to the balance it states, and each balance line
 * below a booking must state the balance the bookings lead to. Stateless: {@link
 * ImportFileParserService} carries the running balance from row to row ({@code null} while
 * unknown), and each method takes it and gives the next.
 *
 * <p>A balance is read with the template's amount rule, parentheses included: a statement that puts
 * a negative amount in parentheses puts a negative balance (a loan account's, all along) in them
 * too. A balance that is no amount (e.g. {@code 1.234,56-} or {@code 1.234,56 S}) states nothing:
 * it checks nothing, and the booking it stands beside keeps its own status, as the balance column
 * only checks the amounts it is read beside. The running balance goes on from the booking's amount,
 * so the next balance that is an amount checks this booking too.
 */
@Service
public class PdfBalanceCheckService {

  /**
   * The balance before {@code booking}: the one a balance line stated since the booking before;
   * without one, none at a section's start, else {@code running}.
   */
  public BigDecimal balanceBefore(
      ImportPdfBookingLine booking, BigDecimal running, ImportTemplateDefinition template) {
    if (booking.statedBalance() == null) {
      return booking.sectionStart() ? null : running;
    }
    return read(booking.statedBalance(), template);
  }

  /**
   * The balance after a booking of {@code amount} that states {@code balanceCell}: the stated
   * balance, or what {@code before} leads to where it states none. A wrong row does not make every
   * row after it wrong: the next one starts from the balance this one states.
   *
   * @throws ImportRowRejectedException {@link ImportRowErrorValues#BALANCE_MISMATCH} when it states
   *     another balance than {@code before} plus {@code amount}
   */
  public BigDecimal balanceAfter(
      BigDecimal before, BigDecimal amount, String balanceCell, ImportTemplateDefinition template) {
    BigDecimal stated = read(balanceCell, template);
    BigDecimal expected = before == null ? null : before.add(amount);
    if (stated != null && expected != null && stated.compareTo(expected) != 0) {
      throw new ImportRowRejectedException(
          ImportRowErrorValues.BALANCE_MISMATCH,
          ImportFileParserService.rowArgs(
              "value", stated.toPlainString(), "expected", expected.toPlainString()));
    }
    return stated == null ? expected : stated;
  }

  /**
   * {@code balance} as written in a balance column or on a balance line; {@code null} when it is
   * none or no amount, which then checks nothing.
   */
  public BigDecimal read(String balance, ImportTemplateDefinition template) {
    if (balance == null || balance.isBlank()) {
      return null;
    }
    try {
      return ImportFileParserService.parseAmount(
          balance,
          "balance",
          template,
          ImportTemplateValues.AMOUNT_NEGATIVE_IN_PARENTHESES.equals(
              template.amountRepresentation()));
    } catch (ImportRowRejectedException e) {
      return null;
    }
  }

  /**
   * The error rows of the balance lines below {@code booking} (before the next booking of its
   * section, e.g. the section's closing balance) that do not state {@code running}, the balance the
   * bookings lead to, numbered from {@code firstRowNumber} on. Such a line means a booking above it
   * was not read as one - say, a line the balance line pattern took for its own. The balance line
   * is then an error row of its own, so the bookings around it, read correctly, still import. Empty
   * when every line adds up, or when nothing is known to check them against.
   */
  public List<ParsedImportRow> balanceLineErrors(
      ImportPdfBookingLine booking,
      BigDecimal running,
      int firstRowNumber,
      ImportTemplateDefinition template) {
    List<ParsedImportRow> errors = new ArrayList<>();
    if (running == null) {
      return errors;
    }
    for (ImportPdfBookingLine.BalanceLine below : booking.balancesAfter()) {
      BigDecimal stated = read(below.balance(), template);
      if (stated != null && stated.compareTo(running) != 0) {
        errors.add(
            ParsedImportRow.error(
                firstRowNumber + errors.size(),
                Map.of(ImportFileParserService.RAW_LINE_KEY, below.line()),
                ImportRowErrorValues.BALANCE_LINE_MISMATCH,
                ImportFileParserService.rowArgs(
                    "value", stated.toPlainString(), "expected", running.toPlainString())));
      }
    }
    return errors;
  }
}

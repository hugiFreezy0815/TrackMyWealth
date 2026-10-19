package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.ImportSnapshotPlan;
import com.trackmywealth.backend.dto.ImportSnapshotValues;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

/**
 * US-07-31 (#366): the one sentence an app shows above a statement's preview - what its commit will
 * do to the imported snapshot of its date, or what keeps it from being committed - in the caller's
 * language (EN/DE). What blocks the commit comes first: unreadable lines, unknown securities, a
 * security listed twice, a reconciliation decision standing on the snapshot; then the snapshot's
 * outcome ({@link ImportSnapshotValues}), before or after the commit. An existing snapshot is named
 * by its date and the file of the import that recorded it.
 */
@Service
public class ImportSnapshotExplanationService {

  private static final String PREFIX = "tmw.import.snapshot.explain.";
  private static final String BALANCE_PREFIX = PREFIX + "balance.";
  private static final String PART_PREFIX = "tmw.import.snapshot.part.";
  private static final String BOTH = "BOTH";
  private static final String WITH = "with.";
  private static final String ADDS = "adds.";

  // A cash statement's rows are committed whatever its balance meets: only these differ in wording.
  private static final Set<String> BALANCE_OUTCOMES =
      Set.of(ImportSnapshotValues.ALREADY_RECORDED, ImportSnapshotValues.CONFLICTING);

  private final MessageSource messageSource;

  public ImportSnapshotExplanationService(MessageSource messageSource) {
    this.messageSource = messageSource;
  }

  /**
   * A holdings statement's sentence.
   *
   * @param outcome the plan's outcome before the commit, or the batch's snapshot record after it
   * @param plan the plan before the commit, {@code null} after it
   */
  public String explain(
      String outcome,
      ImportSnapshotPlan plan,
      LocalDate date,
      int unreadable,
      int unresolved,
      boolean securityTwice,
      Locale locale) {
    if (unreadable > 0) {
      return message(PREFIX + "UNREADABLE", locale, unreadable);
    }
    if (unresolved > 0) {
      return message(PREFIX + "UNRESOLVED", locale, unresolved);
    }
    if (securityTwice) {
      return message(PREFIX + "DUPLICATE_SECURITY", locale);
    }
    if (outcome == null) {
      return null;
    }
    return snapshotSentence(PREFIX, outcome, plan, date, locale);
  }

  /**
   * A cash statement's sentence about its closing balance (US-07-12): unlike a holdings statement,
   * a balance that is recorded already or differs only stays out of the snapshot - the statement's
   * bookings are committed all the same.
   */
  public String explainBalance(
      String outcome, ImportSnapshotPlan plan, LocalDate date, Locale locale) {
    if (outcome == null) {
      return null;
    }
    return snapshotSentence(
        BALANCE_OUTCOMES.contains(outcome) ? BALANCE_PREFIX : PREFIX, outcome, plan, date, locale);
  }

  // {0} the date, {1} the parts added, {2} the number of differences, {3} the earlier import's
  // file name and {4} whether there is one (1) or not (0).
  private String snapshotSentence(
      String prefix, String outcome, ImportSnapshotPlan plan, LocalDate date, Locale locale) {
    String day =
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale));
    if (plan != null && plan.committable() && plan.decided()) {
      return message(PREFIX + "DECIDED", locale, day);
    }
    List<String> parts = plan == null ? List.of() : plan.adds();
    // A new snapshot is recorded "with" its parts, an existing one "adds" them (German cases).
    String phrase = ImportSnapshotValues.NEW.equals(outcome) ? WITH : ADDS;
    // A batch may have no file name (an upload without one): the sentence then names none.
    String file = plan == null || plan.existing() == null ? null : plan.existing().importFileName();
    return message(
        prefix + outcome,
        locale,
        day,
        parts(parts, phrase, locale),
        plan == null ? 0 : plan.conflicts().size(),
        file == null ? "" : file,
        file == null ? 0 : 1);
  }

  private String parts(List<String> parts, String phrase, Locale locale) {
    if (parts.isEmpty()) {
      return "";
    }
    String key = phrase + (parts.size() > 1 ? BOTH : parts.get(0));
    return messageSource.getMessage(PART_PREFIX + key, null, key, locale);
  }

  private String message(String key, Locale locale, Object... args) {
    return messageSource.getMessage(key, args, key, locale);
  }
}

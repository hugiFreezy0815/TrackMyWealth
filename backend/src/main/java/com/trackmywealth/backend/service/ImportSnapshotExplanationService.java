package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.ImportSnapshotPlan;
import com.trackmywealth.backend.dto.ImportSnapshotValues;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

/**
 * US-07-31 (#366): the one sentence an app shows above a holdings statement's preview - what its
 * commit will do, or what keeps it from being committed - in the caller's language (EN/DE). What
 * blocks the commit comes first: unreadable lines, unknown securities, a security listed twice;
 * then the snapshot's outcome ({@link ImportSnapshotValues}), before or after the commit.
 */
@Service
public class ImportSnapshotExplanationService {

  private static final String PREFIX = "tmw.import.snapshot.explain.";
  private static final String PART_PREFIX = "tmw.import.snapshot.part.";
  private static final String BOTH = "BOTH";
  private static final String WITH = "with.";
  private static final String ADDS = "adds.";

  private final MessageSource messageSource;

  public ImportSnapshotExplanationService(MessageSource messageSource) {
    this.messageSource = messageSource;
  }

  /**
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
    String day =
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale));
    if (unreadable > 0) {
      return message("UNREADABLE", locale, unreadable);
    }
    if (unresolved > 0) {
      return message("UNRESOLVED", locale, unresolved);
    }
    if (securityTwice) {
      return message("DUPLICATE_SECURITY", locale);
    }
    if (outcome == null) {
      return null;
    }
    List<String> parts = plan == null ? List.of() : plan.adds();
    // A new snapshot is recorded "with" its parts, an existing one "adds" them (German cases).
    String phrase = ImportSnapshotValues.NEW.equals(outcome) ? WITH : ADDS;
    return message(
        outcome,
        locale,
        day,
        parts(parts, phrase, locale),
        plan == null ? 0 : plan.conflicts().size());
  }

  private String parts(List<String> parts, String phrase, Locale locale) {
    if (parts.isEmpty()) {
      return "";
    }
    String key = phrase + (parts.size() > 1 ? BOTH : parts.get(0));
    return messageSource.getMessage(PART_PREFIX + key, null, key, locale);
  }

  private String message(String key, Locale locale, Object... args) {
    return messageSource.getMessage(PREFIX + key, args, key, locale);
  }
}

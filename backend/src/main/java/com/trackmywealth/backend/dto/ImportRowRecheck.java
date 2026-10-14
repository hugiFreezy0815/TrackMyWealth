package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * What a commit would do now with one preview row whose stored state is out of date (#308): {@code
 * recheck} is one of {@link ImportBatchValues#RECHECK_NEW}, {@link
 * ImportBatchValues#RECHECK_REPOINTED} or {@link ImportBatchValues#RECHECK_DUPLICATE}; {@code
 * included} and {@code duplicateOfTransactionId} are the values the commit uses, {@code
 * wasIncluded} the stored one. Internal, not part of any API response.
 */
public record ImportRowRecheck(
    String recheck, boolean included, UUID duplicateOfTransactionId, boolean wasIncluded) {

  /** A duplicate whose booking is gone: new, and included as it was left out only as one. */
  public static ImportRowRecheck becameNew(boolean wasIncluded) {
    return new ImportRowRecheck(ImportBatchValues.RECHECK_NEW, true, null, wasIncluded);
  }

  /** A duplicate whose booking another ledger row holds now: included or not as before. */
  public static ImportRowRecheck repointed(UUID holder, boolean wasIncluded) {
    return new ImportRowRecheck(
        ImportBatchValues.RECHECK_REPOINTED, wasIncluded, holder, wasIncluded);
  }

  /** An included new row the ledger has gained since: an excluded duplicate of {@code match}. */
  public static ImportRowRecheck becameDuplicate(UUID match) {
    return becameDuplicate(match, true);
  }

  /** For an explicit include PATCH, the original PARSED row may still be excluded. */
  public static ImportRowRecheck becameDuplicate(UUID match, boolean wasIncluded) {
    return new ImportRowRecheck(ImportBatchValues.RECHECK_DUPLICATE, false, match, wasIncluded);
  }

  /** The row status the commit treats it as. */
  public String status() {
    return ImportBatchValues.RECHECK_NEW.equals(recheck)
        ? ImportBatchValues.ROW_PARSED
        : ImportBatchValues.ROW_DUPLICATE;
  }
}

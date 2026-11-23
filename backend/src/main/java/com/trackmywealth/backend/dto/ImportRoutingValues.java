package com.trackmywealth.backend.dto;

/**
 * US-07-34 (#365): the outcomes of an upload without an account ({@code POST /api/v1/imports}) and
 * why an account is offered as a candidate.
 *
 * <ul>
 *   <li>{@link #BATCH_CREATED}: the file names one account for certain (an IBAN or a contract
 *       number) that the member may import into; the preview batch is created on it.
 *   <li>{@link #CHOOSE_ACCOUNT}: no certain account, but candidates; the member picks one and
 *       uploads to that account.
 *   <li>{@link #NO_ACCOUNT}: no candidate; the member creates an account (the proposal, when the
 *       file tells enough) or picks any other.
 *   <li>{@link #NO_TEMPLATE}: no template reads the file unambiguously; the member picks one of the
 *       template candidates and uploads again with its {@code templateId}, or none fits.
 * </ul>
 *
 * Only {@link #BATCH_CREATED} stores anything.
 */
public final class ImportRoutingValues {

  public static final String BATCH_CREATED = "BATCH_CREATED";
  public static final String CHOOSE_ACCOUNT = "CHOOSE_ACCOUNT";
  public static final String NO_ACCOUNT = "NO_ACCOUNT";
  public static final String NO_TEMPLATE = "NO_TEMPLATE";

  /** The file names the account by a masked card number. */
  public static final String REASON_IDENTIFIER = "IDENTIFIER";

  /** The account is at the bank whose template read the file. */
  public static final String REASON_INSTITUTION = "INSTITUTION";

  private ImportRoutingValues() {}
}

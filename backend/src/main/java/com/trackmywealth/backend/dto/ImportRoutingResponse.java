package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * US-07-34 (#365): what the server made of a statement uploaded without an account. {@code outcome}
 * is one of {@link ImportRoutingValues}; {@code batch} is set for {@code BATCH_CREATED} only, the
 * same batch an upload into its account answers. Nothing else is stored.
 */
public record ImportRoutingResponse(
    @Schema(
            allowableValues = {
              ImportRoutingValues.BATCH_CREATED,
              ImportRoutingValues.CHOOSE_ACCOUNT,
              ImportRoutingValues.NO_ACCOUNT,
              ImportRoutingValues.NO_TEMPLATE
            })
        String outcome,
    @Schema(description = "The preview batch, on the account the file names; BATCH_CREATED only.")
        @Nullable ImportBatchResponse batch,
    @Schema(
            description =
                "CHOOSE_ACCOUNT: the accounts the file may belong to, the one it names first."
                    + " Empty for every other outcome.")
        List<ImportAccountCandidateResponse> accountCandidates,
    @Schema(
            description =
                "What the file suggests for a new account, when it names no account the member"
                    + " may import into and a template read it; null otherwise (also for CAMT).")
        @Nullable ImportAccountProposalResponse accountProposal,
    @Schema(
            description =
                "NO_TEMPLATE: the templates that can read the file, best first; empty when none"
                    + " can. Empty for every other outcome.")
        List<ImportTemplateCandidateResponse> templateCandidates) {

  public ImportRoutingResponse {
    accountCandidates = List.copyOf(accountCandidates);
    templateCandidates = List.copyOf(templateCandidates);
  }

  /** {@code BATCH_CREATED} with {@code batch}. */
  public static ImportRoutingResponse created(ImportBatchResponse batch) {
    return new ImportRoutingResponse(
        ImportRoutingValues.BATCH_CREATED, batch, List.of(), null, List.of());
  }

  /** {@code NO_TEMPLATE} with the templates that can read the file. */
  public static ImportRoutingResponse noTemplate(List<ImportTemplateCandidateResponse> candidates) {
    return new ImportRoutingResponse(
        ImportRoutingValues.NO_TEMPLATE, null, List.of(), null, candidates);
  }

  /** {@code CHOOSE_ACCOUNT} with candidates, else {@code NO_ACCOUNT}; the proposal in either. */
  public static ImportRoutingResponse unrouted(
      List<ImportAccountCandidateResponse> candidates,
      @Nullable ImportAccountProposalResponse proposal) {
    return new ImportRoutingResponse(
        candidates.isEmpty() ? ImportRoutingValues.NO_ACCOUNT : ImportRoutingValues.CHOOSE_ACCOUNT,
        null,
        candidates,
        proposal,
        List.of());
  }
}

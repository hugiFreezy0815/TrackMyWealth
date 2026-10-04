package com.trackmywealth.backend.architecture;

import java.util.Map;

/**
 * ADR 0004's "Not read-modify-write" list as code: the mutating handlers that legitimately take no
 * {@code If-Match}, keyed {@code Controller#method}, each with its reason. {@link
 * IfMatchCoverageTest} checks it against the controllers' code and {@code
 * ApiConventionsIntegrationTest} against the generated OpenAPI contract. Adding an entry means
 * updating the ADR too.
 */
public final class IfMatchExceptions {

  public static final Map<String, String> REVIEWED_WITHOUT_IF_MATCH =
      Map.ofEntries(
          // Creates: POST on a collection, no prior version to protect.
          Map.entry("AccountController#createAccount", "create"),
          Map.entry("AccountSnapshotController#recordSnapshot", "create"),
          Map.entry("AdminUserController#createUser", "create"),
          Map.entry("CategorizationRuleController#create", "create"),
          Map.entry("CategoryController#create", "create"),
          Map.entry("CustomAssetValuationController#recordValuation", "create (append-only)"),
          Map.entry("ImportTemplateController#create", "create"),
          Map.entry("InstitutionController#createInstitution", "create"),
          Map.entry("OpeningBalanceController#recordOpeningBalance", "create"),
          Map.entry("SharingGrantController#grant", "create"),
          Map.entry("TransactionController#recordTransaction", "create (append-only ledger)"),
          Map.entry("SecurityController#findOrCreate", "idempotent find-or-create of shared data"),
          // Batch: re-runs matching for a card; idempotent, no client-held state.
          Map.entry("SettlementMatchController#run", "idempotent batch"),
          // Dry runs: parse an uploaded file in memory and write nothing (US-07-03).
          Map.entry("ImportTemplateController#detect", "dry run, writes nothing"),
          Map.entry("ImportTemplateController#testSaved", "dry run, writes nothing"),
          Map.entry("ImportTemplateController#testUnsaved", "dry run, writes nothing"),
          // Credential exchanges and one-time bootstrap, not edits of a versioned resource.
          Map.entry("AuthController#login", "credential exchange"),
          Map.entry("AuthController#refresh", "credential exchange"),
          Map.entry("AuthController#verifyMfa", "credential exchange"),
          Map.entry("SetupController#bootstrapAdministrator", "one-time bootstrap"),
          // The caller's own MFA state machine: each step is authorized by a fresh TOTP code or
          // the password, which already proves the caller acts on the current state.
          Map.entry("MfaController#enroll", "own MFA state, re-authenticated"),
          Map.entry("MfaController#confirm", "own MFA state, re-authenticated"),
          Map.entry("MfaController#disable", "own MFA state, re-authenticated"),
          // Revocation is a terminal, idempotent state: two revokes cannot lose an update.
          Map.entry("SessionController#revokeSession", "idempotent terminal transition"),
          // Mapped for every HTTP method so an error forwarded from any request renders the
          // problem body; it writes nothing.
          Map.entry("ProblemErrorController#error", "error rendering, writes nothing"));

  private IfMatchExceptions() {}
}

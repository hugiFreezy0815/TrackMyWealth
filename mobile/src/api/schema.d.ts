// Generated from api/openapi.yaml by `npm run api:types` (scripts/generate-api-types.mjs).
// Do not edit by hand: change the backend, regenerate the contract, then run the script.
import type { DecimalString } from './decimal';

export interface paths {
  '/api/v1/accounts': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * List the workspace's accounts with their current value
     * @description Every active account of the workspace, or every archived one with archived=true, oldest first. Not paged: a workspace holds tens of accounts. Amounts are signed (assets positive, liabilities negative) and come from the same valuation as net worth.
     */
    get: operations['listAccounts'];
    put?: never;
    /** Create an account */
    post: operations['createAccount'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read an account */
    get: operations['getAccount'];
    /** Change an account's name and details */
    put: operations['updateAccount'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/amortisation-schedule': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** List a loan's amortisation schedule */
    get: operations['listAmortisationEntries'];
    put?: never;
    /** Add an amortisation schedule entry */
    post: operations['createAmortisationEntry'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/amortisation-schedule/{entryId}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /** Replace an amortisation schedule entry */
    put: operations['replaceAmortisationEntry'];
    post?: never;
    /** Delete an amortisation schedule entry */
    delete: operations['deleteAmortisationEntry'];
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/archive': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Archive an account */
    post: operations['archiveAccount'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/balance': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** An account's current value */
    get: operations['getAccountBalance'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/identifier': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read an account's masked identifiers */
    get: operations['getAccountIdentifier'];
    /** Register an account's IBAN, card number or contract number */
    put: operations['setAccountIdentifier'];
    post?: never;
    /** Remove an account's identifiers */
    delete: operations['clearAccountIdentifier'];
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/identifier/from-import': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /** Take an account's identifier from an imported file */
    put: operations['setAccountIdentifierFromImport'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/imports': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** List an account's imports */
    get: operations['listImportBatches'];
    put?: never;
    /** Upload a statement file for import */
    post: operations['createImportBatch'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/imports/{batchId}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read an import */
    get: operations['getImportBatch'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/imports/{batchId}/account-proposal': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Propose a new account from an import */
    post: operations['proposeAccountFromImportBatch'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/imports/{batchId}/commit': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Book an import's rows */
    post: operations['commitImportBatch'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/imports/{batchId}/confirm-account': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Confirm the account an import belongs to */
    post: operations['confirmImportBatchAccount'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/imports/{batchId}/discard': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Discard an import that was not booked */
    post: operations['discardImportBatch'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/imports/{batchId}/errors.csv': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Download an import's row errors as CSV */
    get: operations['getImportBatchErrors'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/imports/{batchId}/parse': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Read an uploaded file into rows to preview */
    post: operations['parseImportBatch'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/imports/{batchId}/rollback': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Undo a booked import */
    post: operations['rollbackImportBatch'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/imports/{batchId}/rows': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** List an import's preview rows */
    get: operations['listImportBatchRows'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/imports/{batchId}/rows/{rowNumber}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    /** Include or leave out one preview row */
    patch: operations['setImportRowIncluded'];
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/opening-balance': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read an account's opening balance */
    get: operations['getOpeningBalance'];
    /** Replace an account's opening balance */
    put: operations['replaceOpeningBalance'];
    /** Record an account's opening balance */
    post: operations['recordOpeningBalance'];
    /** Delete an account's opening balance */
    delete: operations['deleteOpeningBalance'];
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/positions': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read positions from the account's newest holdings snapshot */
    get: operations['getAccountPositions'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/reassign-portfolio': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Move an account to another portfolio */
    post: operations['reassignAccountPortfolio'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/reconciliations': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** List an account's reconciliation results */
    get: operations['listReconciliations'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/reconciliations/{resultId}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read a reconciliation result */
    get: operations['getReconciliation'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/reconciliations/{resultId}/accept': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Accept a difference with an adjusting entry */
    post: operations['acceptReconciliation'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/reconciliations/{resultId}/dismiss': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Dismiss a difference */
    post: operations['dismissReconciliation'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/reconciliations/{resultId}/reopen': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Reopen an accepted or dismissed difference */
    post: operations['reopenReconciliation'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/repayments': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** List a loan's repayments */
    get: operations['listLoanRepayments'];
    put?: never;
    /** Record a loan repayment */
    post: operations['recordLoanRepayment'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/restore': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Restore an archived account */
    post: operations['restoreAccount'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/savings-pocket': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /**
     * Link another account as the account's savings pocket
     * @description The next import commits book the account's savings pocket moves on this account, e.g. after the pocket an import created was archived. The pocket must be an active account in the account's currency that holds transactions, is no card or loan and has no pocket of its own (422 SAVINGS_POCKET_UNSUITABLE), and no other account's pocket (409 SAVINGS_POCKET_IN_USE). A card, a loan, an account without transactions or another account's pocket can have none (422 SAVINGS_POCKET_NOT_APPLICABLE). A link is replaced, never removed.
     */
    put: operations['linkAccountSavingsPocket'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/snapshots': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** List an account's snapshots */
    get: operations['listAccountSnapshots'];
    put?: never;
    /** Record a balance or holdings snapshot */
    post: operations['recordAccountSnapshot'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/snapshots/{snapshotId}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read a snapshot */
    get: operations['getAccountSnapshot'];
    /** Replace a snapshot */
    put: operations['replaceAccountSnapshot'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/transactions': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** List an account's transactions */
    get: operations['listAccountTransactions'];
    put?: never;
    /** Record a transaction */
    post: operations['recordTransaction'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/transactions/deleted': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * List an account's removed transactions
     * @description Fixed order: most recently removed first (deletedAt, or voidedAt for a voided row, descending), then id descending. Takes page and size only; any sort is a 400 INVALID_SORT.
     */
    get: operations['listRemovedTransactions'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/transactions/{transactionId}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /** Correct a transaction */
    put: operations['correctTransaction'];
    post?: never;
    /** Remove a transaction */
    delete: operations['removeTransaction'];
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/transactions/{transactionId}/category': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /** Set a transaction's category by hand */
    put: operations['overrideTransactionCategory'];
    post?: never;
    /** Return a transaction to its automatic category */
    delete: operations['resetTransactionCategory'];
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/transactions/{transactionId}/restore': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Restore a removed transaction */
    post: operations['restoreTransaction'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/transactions/{transactionId}/untracked-transfer': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Mark a transaction as a transfer to an untracked account */
    post: operations['confirmUntrackedTransfer'];
    /** Undo marking a transaction as an untracked transfer */
    delete: operations['undoUntrackedTransfer'];
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{accountId}/valuations': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** List an asset's manual valuations */
    get: operations['listAccountValuations'];
    put?: never;
    /** Record a manual valuation of an asset */
    post: operations['recordAccountValuation'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{cardAccountId}/settlement-matches/run': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Match card payments to statements now */
    post: operations['runSettlementMatching'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{cardAccountId}/settlement-source': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read the account a card is paid from */
    get: operations['getSettlementSource'];
    /** Set the account a card is paid from */
    put: operations['setSettlementSource'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{cardAccountId}/statement': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** A card's current statement and due date */
    get: operations['getCurrentCardStatement'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/accounts/{cardAccountId}/statement-config': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read a card's statement cycle */
    get: operations['getCardStatementConfig'];
    /** Set a card's statement cycle */
    put: operations['setCardStatementConfig'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/admin/fx-import': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read the FX rate import settings */
    get: operations['getFxImportSettings'];
    /** Change how often FX rates are imported */
    put: operations['updateFxImportInterval'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/admin/reference-data': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read the loaded reference-data package */
    get: operations['getReferenceData'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/admin/users': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Create a user */
    post: operations['createUser'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/admin/users/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read a user */
    get: operations['getUser'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    /** Change a user's email, role or language */
    patch: operations['updateUser'];
    trace?: never;
  };
  '/api/v1/admin/users/{id}/disable': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Disable a user */
    post: operations['disableUser'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/admin/users/{id}/reactivate': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Reactivate a disabled user */
    post: operations['reactivateUser'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/auth/login': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Sign in with email and password */
    post: operations['login'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/auth/mfa/verify': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Complete a sign-in with a one-time code */
    post: operations['verifyMfa'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/auth/refresh': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Exchange a refresh token for new tokens */
    post: operations['refreshToken'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/cash-flow': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Income and spending per category for a month */
    get: operations['getCashFlow'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/categories': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** List the categories */
    get: operations['listCategories'];
    put?: never;
    /** Create a category */
    post: operations['createCategory'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/categories/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read a category */
    get: operations['getCategory'];
    /** Rename a category or change its parent */
    put: operations['updateCategory'];
    post?: never;
    /** Delete an unused category */
    delete: operations['deleteCategory'];
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/categories/{id}/activate': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Reactivate a category */
    post: operations['activateCategory'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/categories/{id}/deactivate': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Deactivate a category */
    post: operations['deactivateCategory'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/categorization-rules': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** List the categorization rules */
    get: operations['listCategorizationRules'];
    put?: never;
    /** Create a categorization rule */
    post: operations['createCategorizationRule'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/categorization-rules/{id}/deactivate': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Deactivate a categorization rule */
    post: operations['deactivateCategorizationRule'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/import-templates': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** List the import templates */
    get: operations['listImportTemplates'];
    put?: never;
    /** Create an import template */
    post: operations['createImportTemplate'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/import-templates/detect': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Find the templates that can read a file */
    post: operations['detectImportTemplate'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/import-templates/test': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Try a template that is not saved yet on a file */
    post: operations['testUnsavedImportTemplate'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/import-templates/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read an import template */
    get: operations['getImportTemplate'];
    /** Change an import template */
    put: operations['updateImportTemplate'];
    post?: never;
    /** Delete an import template */
    delete: operations['deleteImportTemplate'];
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/import-templates/{id}/activate': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Reactivate an import template */
    post: operations['activateImportTemplate'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/import-templates/{id}/deactivate': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Deactivate an import template */
    post: operations['deactivateImportTemplate'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/import-templates/{id}/test': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Try a saved template on a file */
    post: operations['testImportTemplate'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/import-templates/{templateId}/account-proposal': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Propose a new account from a statement file */
    post: operations['proposeAccountFromFile'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/institutions/catalogue': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Search the institution catalogue */
    get: operations['searchInstitutionCatalogue'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/net-worth': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** The workspace's net worth */
    get: operations['getNetWorth'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/portfolios': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * List the workspace's portfolios with their totals
     * @description Every active portfolio of the workspace, the Personal Assets default included, in creation order. Not paged: a workspace holds a handful of portfolios. Each row has the number of active accounts and their signed total (assets positive, liabilities negative) in the portfolio's and the workspace's currency, from the same valuation as the Portfolio View and net worth.
     */
    get: operations['listPortfolios'];
    put?: never;
    /** Create a portfolio */
    post: operations['createPortfolio'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/portfolios/{portfolioId}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read a portfolio */
    get: operations['getPortfolio'];
    /** Rename a portfolio, describe its institution or set its type */
    put: operations['updatePortfolio'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/portfolios/{portfolioId}/summary': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Total assets, liabilities and net value of a portfolio */
    get: operations['getPortfolioSummary'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/portfolios/{portfolioId}/view': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** A portfolio's tab: its total and a section per kind of account */
    get: operations['getPortfolioView'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/securities': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Find a security by ISIN */
    get: operations['findSecurity'];
    put?: never;
    /** Find a security or create it */
    post: operations['findOrCreateSecurity'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/securities/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read a security */
    get: operations['getSecurity'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/sessions': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** List the signed-in user's sessions */
    get: operations['listSessions'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/sessions/{id}/revoke': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Sign out one session */
    post: operations['revokeSession'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/settlement-matches': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * List settlement matches
     * @description Matches of one status, newest first; by default those needing a decision.
     */
    get: operations['listSettlementMatches'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/settlement-matches/{matchId}/confirm': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Confirm a settlement match */
    post: operations['confirmSettlementMatch'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/settlement-matches/{matchId}/reject': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Reject a settlement match */
    post: operations['rejectSettlementMatch'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/setup/administrator': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Create the first administrator and the workspace */
    post: operations['setupAdministrator'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/transactions': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** List the workspace's transactions across all accounts */
    get: operations['listWorkspaceTransactions'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/users/me': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read the signed-in user */
    get: operations['getCurrentUser'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/users/me/mfa/confirm': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Confirm the authenticator app with a first code */
    post: operations['confirmMfa'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/users/me/mfa/disable': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Turn off two-factor sign-in */
    post: operations['disableMfa'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/users/me/mfa/enroll': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Start enrolling an authenticator app */
    post: operations['enrollMfa'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/workspace': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read the workspace */
    get: operations['getWorkspace'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/workspace-members': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** List the workspace's members */
    get: operations['listWorkspaceMembers'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/workspace-members/{memberId}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Read a workspace member */
    get: operations['getWorkspaceMember'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/workspace-members/{memberId}/deactivate': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Deactivate a workspace member */
    post: operations['deactivateWorkspaceMember'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/workspace/currency': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /** Change the workspace's currency */
    put: operations['updateWorkspaceCurrency'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/workspace/name': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /** Rename or clear the workspace name */
    put: operations['updateWorkspaceName'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
}
export type webhooks = Record<string, never>;
export interface components {
  schemas: {
    AccountContribution: {
      /** Format: uuid */
      accountId: string;
      name: string;
      nature: string;
      nativeCurrency: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      value: DecimalString | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      conversionRate: DecimalString | null;
      /** Format: date */
      conversionRateDate: string | null;
      conversionRateCarriedForward: boolean;
      conversionRateStale: boolean;
      valueKnown: boolean;
      /** Format: date */
      valueSourceDate: string | null;
      valueSourceStale: boolean;
      warnings: string[];
    };
    AccountIdentifierFromImportRequest: {
      /**
       * Format: uuid
       * @description The account the import batch belongs to.
       */
      importAccountId: string;
      /**
       * Format: uuid
       * @description An open import batch (UPLOADED or PARSED) whose file names the account.
       */
      batchId: string;
    };
    AccountIdentifierRequest: {
      /** @description An IBAN; for a credit card account, its card number masked except for at most the first 6 and the last 4 digits. */
      identifier: string;
      /** @description IBAN, MASKED_CARD or CONTRACT_NUMBER (a ten-digit contract number, kept beside the IBAN); the account's own kind when omitted. */
      kind?: string;
    };
    AccountIdentifierResponse: {
      /** Format: uuid */
      accountId: string;
      kind: string;
      identifierMasked: string | null;
      /** Format: int32 */
      version: number;
      contractIdentifierMasked: string | null;
    };
    AccountListItemResponse: {
      /** Format: uuid */
      id: string;
      /** Format: int32 */
      version: number;
      name: string;
      accountType: string;
      nature: string;
      /** Format: uuid */
      portfolioId: string;
      portfolioName: string;
      currency: string;
      archived: boolean;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      balance: DecimalString | null;
      workspaceCurrency: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      balanceInWorkspaceCurrency: DecimalString | null;
      valueKnown: boolean;
      /** @enum {string} */
      valueStatus: 'CURRENT' | 'ESTIMATED' | 'STALE' | 'UNRECONCILED' | 'UNKNOWN';
      /** Format: date */
      asOf: string;
      /** Format: date */
      valueSourceDate: string | null;
      warnings: string[];
    };
    AccountPositionResponse: {
      /** Format: uuid */
      securityId: string;
      displayName: string;
      isin: string | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      quantity: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      price: DecimalString | null;
      /** @description Currency of price and value, as the statement printed them. */
      priceCurrency: string | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      value: DecimalString | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      valueInAccountCurrency: DecimalString | null;
      /**
       * Format: decimal
       * @description Cost basis as the statement printed it, never converted.
       */
      costBasis: DecimalString | null;
      costBasisEstimated: boolean;
      /**
       * Format: decimal
       * @description valueInAccountCurrency minus costBasis. Null without a cost basis or value, and when priceCurrency differs from the account currency: the cost basis carries no currency, so the gain would not be certain.
       */
      unrealisedGain: DecimalString | null;
      /**
       * Format: decimal
       * @description unrealisedGain in percent of costBasis; null when unrealisedGain is.
       */
      unrealisedGainPercent: DecimalString | null;
      /** @enum {string} */
      valueStatus: 'CURRENT' | 'ESTIMATED' | 'STALE' | 'UNRECONCILED' | 'UNKNOWN';
    };
    AccountPositionsResponse: {
      /** Format: uuid */
      accountId: string;
      currency: string;
      /** Format: date */
      asOf: string | null;
      /** @enum {string} */
      source: 'HOLDINGS_SNAPSHOT';
      complete: boolean;
      /** @description All holdings of one snapshot, by valueInAccountCurrency descending (unknown values last), then securityId. Not paged: a snapshot holds at most 2000 holdings, the most one holdings import accepts. */
      positions: components['schemas']['AccountPositionResponse'][];
    };
    AccountRow: {
      /** Format: uuid */
      accountId: string;
      name: string;
      accountType: string;
      nature: string;
      currency: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      balance: DecimalString | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      valueInPortfolioCurrency: DecimalString | null;
      valueKnown: boolean;
      /** @enum {string} */
      valueStatus: 'CURRENT' | 'ESTIMATED' | 'STALE' | 'UNRECONCILED' | 'UNKNOWN';
      /** Format: date */
      valueSourceDate: string | null;
      warnings: string[];
      /** Format: int32 */
      positionsCount: number | null;
      /** Format: date */
      positionsAsOf: string | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      totalReturn: DecimalString | null;
      /** Format: date */
      dueDate: string | null;
      /** Format: uuid */
      linkedPropertyAccountId: string | null;
      maskedIdentifier: string | null;
      reconciliationStatus: string;
      /** Format: date */
      statementDate: string | null;
    };
    AccountSnapshotResponse: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      accountId: string;
      /** Format: date */
      snapshotDate: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      balance: DecimalString | null;
      currency: string;
      source: string;
      openingBalance: boolean;
      /** Format: date-time */
      createdAt: string;
      /** Format: date-time */
      updatedAt: string | null;
      /** Format: int32 */
      version: number;
      holdings: components['schemas']['SnapshotHoldingResponse'][];
    };
    AccountSummaryResponse: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      portfolioId: string;
      name: string;
      accountType: string;
      nativeCurrency: string;
      nature: string;
      holdsPositions: boolean;
      hasTransactions: boolean;
      hasStatementCycle: boolean;
      hasAmortisation: boolean;
      hasContributionLimit: boolean;
      discretionary: boolean;
      manualValuation: boolean;
      countsAsSaving: boolean;
      /** Format: uuid */
      savingsPocketAccountId: string | null;
      status: string;
      /** Format: date-time */
      archivedAt: string | null;
      /** Format: int32 */
      version: number;
      warnings: string[];
      reconciliation: components['schemas']['ReconciliationStatusResponse'];
    };
    AccountValuation: {
      /** Format: uuid */
      accountId: string;
      name: string;
      nature: string;
      nativeCurrency: string;
      currency: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      value: DecimalString | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      conversionRate: DecimalString | null;
      /** Format: date */
      conversionRateDate: string | null;
      conversionRateCarriedForward: boolean;
      conversionRateStale: boolean;
      valueKnown: boolean;
      valueBasis: string | null;
      /** Format: date */
      valueSourceDate: string | null;
      valueSourceStale: boolean;
      warnings: string[];
    };
    AmortisationScheduleEntryRequest: {
      /** Format: date */
      dueDate: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      interestComponent: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      principalComponent: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      remainingBalance: DecimalString;
    };
    AmortisationScheduleEntryResponse: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      accountId: string;
      /** Format: date */
      dueDate: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      interestComponent: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      principalComponent: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      remainingBalance: DecimalString;
      currency: string;
      /** Format: int32 */
      version: number;
      /** Format: date-time */
      createdAt: string;
    };
    /** @description RFC 9457 problem detail extended with a stable application code and correlation id. */
    ApiProblem: {
      title: string;
      /** Format: int32 */
      status: number;
      detail?: string;
      /** @description Stable application error code (docs/api/error-codes.md); each operation lists the codes it can return. An open set: new codes are added without a new API version, so a client must handle a code it does not know by the response's HTTP status. Existing codes are never renamed, and removed only as a declared breaking change. */
      code: string;
      /** @description Request correlation id; quote it when reporting an error. */
      correlationId?: string;
      /** Format: uri */
      instance?: string;
      /** @description Present on bean-validation failures; one entry per rejected field. */
      errors?: {
        field?: string;
        message?: string;
      }[];
    };
    AuthTokensResponse: {
      accessToken: string;
      refreshToken: string;
      tokenType: string;
      /** Format: int64 */
      expiresInSeconds: number;
    };
    CanonicalImportRow: {
      /** Format: date */
      bookingDate: string;
      /** Format: date */
      valueDate: string | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      amount: DecimalString;
      currency: string;
      transactionType: string;
      description: string | null;
      counterpartyName: string | null;
      externalId: string | null;
      mcc: string | null;
      iso20022BankTransactionCode: string | null;
      notes: string | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      originalAmount: DecimalString | null;
      originalCurrency: string | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      bankExchangeRate: DecimalString | null;
      valueDateAdjusted: boolean;
      groupReference: string | null;
      savingsPocket: boolean;
      savingsPocketOtherCurrency: boolean;
    };
    CardStatementResponse: {
      /** Format: uuid */
      cardAccountId: string;
      /** Format: date */
      periodStart: string;
      /** Format: date */
      periodEnd: string;
      /** Format: date */
      dueDate: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      closingBalance: DecimalString | null;
      currency: string;
      paid: boolean;
    };
    Cash: {
      /** Format: int32 */
      rowNumber: number;
      currency: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      amount: DecimalString;
      recorded: boolean;
    };
    CashFlowResponse: {
      month: string;
      income: components['schemas']['CurrencyAmount'][];
      spending: components['schemas']['CurrencyAmount'][];
      saving: components['schemas']['CurrencyAmount'][];
      pendingReview: components['schemas']['CurrencyAmount'][];
      complete: boolean;
    };
    CategorizationRuleResponse: {
      /** Format: uuid */
      id: string;
      matchType: string;
      matchValue: string;
      /** Format: uuid */
      categoryId: string;
      /** Format: int32 */
      priority: number;
      active: boolean;
      /** Format: date-time */
      createdAt: string;
      /** Format: int32 */
      version: number;
    };
    CategoryResponse: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      parentId: string | null;
      code: string;
      nameEn: string;
      nameDe: string;
      /** Format: int32 */
      level: number;
      active: boolean;
      systemDefault: boolean;
      protectedCategory: boolean;
      customised: boolean;
      /** Format: int32 */
      version: number;
    };
    Conflict: {
      /** @enum {string} */
      part: 'BALANCE' | 'HOLDING';
      /** Format: uuid */
      securityId: string | null;
      isin: string | null;
      securityName: string | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      existingValue: DecimalString | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      statementValue: DecimalString | null;
    };
    Contents: {
      /** Format: int64 */
      catalogueInstitutions: number;
      /** Format: int64 */
      defaultCategories: number;
      /** Format: int64 */
      sourceCodeMappings: number;
      /** Format: int64 */
      fallbackSectors: number;
      gicsStructureVersion: string;
    };
    CorrectTransactionRequest: {
      /** Format: uuid */
      targetAccountId?: string;
      transactionType: string;
      /** Format: date */
      bookingDate: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      amount: DecimalString;
      currency: string;
      merchantDescription?: string;
      mcc?: string;
      notes?: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      fxRateToAccountCurrency?: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      billedAmount?: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      feeAmount?: DecimalString;
      /** Format: uuid */
      securityId?: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      quantity?: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      unitPrice?: DecimalString;
      /** Format: date */
      tradeDate?: string;
      /** Format: date */
      settlementDate?: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      grossAmount?: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      taxWithheldAmount?: DecimalString;
      /** Format: uuid */
      counterpartyAccountId?: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      counterpartyAmount?: DecimalString;
      reason?: string;
      counterpartyName?: string;
    };
    CreateAccountRequest: {
      /** Format: uuid */
      portfolioId?: string;
      name: string;
      accountType: string;
      nativeCurrency: string;
      holdsPositions?: boolean;
      billingCurrency?: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      originalPrincipal?: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      interestRatePercent?: DecimalString;
      pensionScheme?: string;
      customAssetType?: string;
      countsAsSaving?: boolean;
    };
    CreateCategorizationRuleRequest: {
      matchType: string;
      matchValue: string;
      /** Format: uuid */
      categoryId: string;
      /** Format: int32 */
      priority?: number;
    };
    CreateCategoryRequest: {
      /** Format: uuid */
      parentId?: string;
      nameEn: string;
      nameDe: string;
    };
    CreateCustomAssetValuationRequest: {
      /** Format: date */
      valuationDate: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      value: DecimalString;
    };
    CreateLoanRepaymentRequest: {
      /** Format: uuid */
      payingAccountId: string;
      /** Format: date */
      bookingDate: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      amount: DecimalString;
      currency: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      interestAmount?: DecimalString;
      merchantDescription?: string;
      notes?: string;
      externalId?: string;
    };
    CreatePortfolioRequest: {
      /** Format: uuid */
      catalogueInstitutionId?: string;
      name?: string;
      country?: string;
      institutionType?: string;
      identifier?: string;
      logoUrl?: string;
      portfolioCurrency?: string;
    };
    CreateSecurityRequest: {
      isin?: string;
      displayName: string;
      denominationCurrency: string;
      instrumentType: string;
      assetClass: string;
      securityCountry?: string;
      issuerCountry?: string;
      legalName?: string;
      idempotencyKey?: string;
      ticker?: string;
    };
    CreateTransactionRequest: {
      transactionType: string;
      /** Format: date */
      bookingDate: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      amount: DecimalString;
      currency: string;
      merchantDescription?: string;
      mcc?: string;
      notes?: string;
      externalId?: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      fxRateToAccountCurrency?: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      billedAmount?: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      feeAmount?: DecimalString;
      /** Format: uuid */
      securityId?: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      quantity?: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      unitPrice?: DecimalString;
      /** Format: date */
      tradeDate?: string;
      /** Format: date */
      settlementDate?: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      grossAmount?: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      taxWithheldAmount?: DecimalString;
      /** Format: uuid */
      counterpartyAccountId?: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      counterpartyAmount?: DecimalString;
      counterpartyName?: string;
    };
    CreateUserRequest: {
      /** Format: email */
      email: string;
      password: string;
      role: string;
      language: string;
    };
    CurrencyAmount: {
      currency: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      amount: DecimalString | null;
      valueKnown: boolean;
      conversionRateCarriedForward: boolean;
      conversionRateStale: boolean;
    };
    CurrentUserResponse: {
      /** Format: uuid */
      userId: string;
      role: string;
      language: string;
      /** Format: uuid */
      sessionId: string;
    };
    CustomAssetValuationResponse: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      accountId: string;
      /** Format: date */
      valuationDate: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      value: DecimalString;
      currency: string;
      source: string;
      /** Format: date-time */
      createdAt: string;
    };
    EditUserRequest: {
      /** Format: email */
      email?: string;
      role?: string;
      language?: string;
    };
    Existing: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      importBatchId: string;
      importFileName: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      balance: DecimalString | null;
      /** Format: int32 */
      holdingsCount: number;
      /** Format: uuid */
      reconciliationResultId: string | null;
    };
    FxImportSettingsResponse: {
      enabled: boolean;
      /**
       * Format: int32
       * @enum {integer|null}
       */
      intervalHours: '1' | '2' | '6' | '12' | '24' | null;
      /** @enum {string} */
      intervalSource: 'ADMINISTRATOR' | 'ENVIRONMENT';
      cron: string;
      cronZone: string;
      /** Format: date-time */
      nextRunAt: string | null;
      /** Format: date */
      lastRateDate: string | null;
      allowedIntervalHours: number[];
      /** Format: int32 */
      version: number;
    };
    ImportAccountIdentifierRule: {
      /** @description IBAN, MASKED_CARD or CONTRACT_NUMBER. */
      kind?: string;
      /** @description PREAMBLE_LINE: the text the identifier's line starts with. */
      label?: string;
      /** @description PREAMBLE_LINE, optional: an RE2 pattern with one capture group for the identifier in the rest of the line. */
      pattern?: string;
      /** @description COLUMN: the source column, by header name or 0-based index. */
      column?: string;
    };
    ImportAccountIdentifierRuleResponse: {
      /** @description IBAN, MASKED_CARD or CONTRACT_NUMBER. */
      kind: string;
      /** @description PREAMBLE_LINE: the text the identifier's line starts with. */
      label: string | null;
      /** @description PREAMBLE_LINE, optional: an RE2 pattern with one capture group for the identifier in the rest of the line. */
      pattern: string | null;
      /** @description COLUMN: the source column, by header name or 0-based index. */
      column: string | null;
    };
    ImportAccountProposalResponse: {
      /**
       * Format: uuid
       * @description The template version the file was read with.
       */
      templateId: string;
      /**
       * Format: uuid
       * @description The institution catalogue entry of the template, not a workspace institution.
       */
      institutionCatalogueId: string | null;
      /** @description That catalogue entry's name, for display. */
      institutionName: string | null;
      /** @description CREDIT_CARD for a card statement, otherwise CASH. */
      suggestedAccountType: string;
      /** @description The one currency every readable row is in; null when the rows have several, or none and the template fixes none. */
      suggestedCurrency: string | null;
      /** @description The account identifier the file names, masked: an IBAN's last four characters, or a card number as masked as the statement had it; null when the template identifies no account or the file names none. */
      maskedIdentifier: string | null;
    };
    ImportBatchCountsResponse: {
      /** Format: int32 */
      total: number;
      /** Format: int32 */
      newRows: number;
      /** Format: int32 */
      duplicates: number;
      /** Format: int32 */
      errors: number;
      /** Format: int32 */
      skipped: number;
      /** Format: int32 */
      warnings: number;
      /** Format: int32 */
      included: number;
      /** Format: int32 */
      imported: number;
    };
    ImportBatchResponse: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      accountId: string;
      status: string;
      sourceKind: string;
      sourceFileName: string | null;
      /** Format: uuid */
      templateId: string | null;
      templateVersion: string | null;
      counts: components['schemas']['ImportBatchCountsResponse'];
      sameFileImportedIn: components['schemas']['ImportSameFileResponse'] | null;
      templateCandidates: components['schemas']['ImportTemplateCandidateResponse'][];
      /** Format: date-time */
      uploadedAt: string;
      /** Format: date-time */
      parsedAt: string | null;
      /** Format: date-time */
      committedAt: string | null;
      /** Format: date-time */
      rolledBackAt: string | null;
      rollbackReason: string | null;
      accountIdentification: components['schemas']['ImportIdentificationResponse'] | null;
      statementBalance: components['schemas']['ImportStatementBalanceResponse'] | null;
      holdings: components['schemas']['ImportHoldingsResponse'] | null;
      /** Format: int32 */
      version: number;
    };
    /** @description Source column per canonical field: a header cell's text, or a 0-based column index written as digits (files without a header row, or repeated header names). */
    ImportColumnMapping: {
      /** @description Required. */
      bookingDate?: string;
      valueDate?: string;
      /** @description Required for SINGLE_SIGNED_COLUMN and NEGATIVE_IN_PARENTHESES. */
      amount?: string;
      /** @description Required for SEPARATE_DEBIT_CREDIT. */
      debitAmount?: string;
      /** @description Required for SEPARATE_DEBIT_CREDIT. */
      creditAmount?: string;
      /** @description Required for currency mode PER_ROW. */
      currency?: string;
      description?: string;
      counterpartyName?: string;
      externalId?: string;
      transactionType?: string;
      mcc?: string;
      iso20022BankTransactionCode?: string;
      notes?: string;
      originalAmount?: string;
      originalCurrency?: string;
      exchangeRate?: string;
      /** @description A reference several bookings share, e.g. both legs of a currency exchange; never a booking's own id. */
      groupReference?: string;
    };
    /** @description Source column per canonical field: a header cell's text, or a 0-based column index written as digits (files without a header row, or repeated header names). */
    ImportColumnMappingResponse: {
      /** @description Required. */
      bookingDate: string | null;
      valueDate: string | null;
      /** @description Required for SINGLE_SIGNED_COLUMN and NEGATIVE_IN_PARENTHESES. */
      amount: string | null;
      /** @description Required for SEPARATE_DEBIT_CREDIT. */
      debitAmount: string | null;
      /** @description Required for SEPARATE_DEBIT_CREDIT. */
      creditAmount: string | null;
      /** @description Required for currency mode PER_ROW. */
      currency: string | null;
      description: string | null;
      counterpartyName: string | null;
      externalId: string | null;
      transactionType: string | null;
      mcc: string | null;
      iso20022BankTransactionCode: string | null;
      notes: string | null;
      originalAmount: string | null;
      originalCurrency: string | null;
      exchangeRate: string | null;
      /** @description A reference several bookings share, e.g. both legs of a currency exchange; never a booking's own id. */
      groupReference: string | null;
    };
    ImportDuplicateOfResponse: {
      /** Format: date */
      bookingDate: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      amount: DecimalString;
      currency: string;
      merchantDescription: string | null;
    };
    ImportHoldingsColumns: {
      isin?: string;
      symbol?: string;
      name?: string;
      quantity?: string;
      price?: string;
      value?: string;
      currency?: string;
      cashAmount?: string;
    };
    ImportHoldingsColumnsResponse: {
      isin: string | null;
      symbol: string | null;
      name: string | null;
      quantity: string | null;
      price: string | null;
      value: string | null;
      currency: string;
      cashAmount: string | null;
    };
    ImportHoldingsResponse: {
      /** Format: date */
      snapshotDate: string | null;
      /** @enum {string|null} */
      dateSource: 'STATEMENT' | 'MEMBER' | null;
      /** @enum {string} */
      snapshot:
        | 'NEW'
        | 'COMPLETES'
        | 'ALREADY_RECORDED'
        | 'CONFLICTING'
        | 'OUT_OF_RANGE'
        | 'RECORDED'
        | 'COMPLETED'
        | 'DELETED'
        | 'WITHDRAWN'
        | 'REMOVED';
      existingSnapshot: components['schemas']['Existing'] | null;
      /** @enum {array} */
      adds: 'BALANCE' | 'HOLDINGS';
      conflicts: components['schemas']['Conflict'][];
      explanation: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      balance: DecimalString | null;
      balanceCurrency: string | null;
      positions: components['schemas']['Position'][];
      cashBalances: components['schemas']['Cash'][];
      unreadable: components['schemas']['Unreadable'][];
      /** Format: int32 */
      unresolvedCount: number;
      readyToCommit: boolean;
    };
    ImportHoldingsRule: {
      /** @description PDF: text every statement of this layout contains. */
      documentMarker?: string;
      /** @description PDF: RE2 pattern with the one named group date, e.g. Portfolio per (?P<date>\S+); its first readable match dates the snapshot. */
      datePattern?: string;
      /** @description Optional: the date's format, if not the template's date format. */
      dateFormat?: string;
      /** @description PDF, optional: RE2 pattern with the one named group currency, starting a section of positions quoted in it, e.g. ^Aktien in (?P<currency>[A-Z]{3})$. */
      sectionPattern?: string;
      /** @description PDF: RE2 patterns of a position line, with the named groups quantity and value, isin and/or symbol, and optionally price, name and currency. */
      positionPatterns?: string[];
      /** @description PDF, optional: RE2 pattern of a cash balance line with the named groups currency and amount. */
      cashPattern?: string;
      /** @description Spreadsheet: the header cells a position or cash row is read from. */
      columns?: components['schemas']['ImportHoldingsColumns'];
    };
    ImportHoldingsRuleResponse: {
      /** @description PDF: text every statement of this layout contains. */
      documentMarker: string | null;
      /** @description PDF: RE2 pattern with the one named group date, e.g. Portfolio per (?P<date>\S+); its first readable match dates the snapshot. */
      datePattern: string | null;
      /** @description Optional: the date's format, if not the template's date format. */
      dateFormat: string | null;
      /** @description PDF, optional: RE2 pattern with the one named group currency, starting a section of positions quoted in it, e.g. ^Aktien in (?P<currency>[A-Z]{3})$. */
      sectionPattern: string | null;
      /** @description PDF: RE2 patterns of a position line, with the named groups quantity and value, isin and/or symbol, and optionally price, name and currency. */
      positionPatterns: string[];
      /** @description PDF, optional: RE2 pattern of a cash balance line with the named groups currency and amount. */
      cashPattern: string | null;
      /** @description Spreadsheet: the header cells a position or cash row is read from. */
      columns: components['schemas']['ImportHoldingsColumnsResponse'] | null;
    };
    ImportIdentificationResponse: {
      outcome: string;
      /** Format: uuid */
      proposedAccountId: string | null;
      confirmationRequired: boolean;
      confirmed: boolean;
    };
    ImportParseRequest: {
      /** Format: uuid */
      templateId?: string;
      /** Format: date */
      snapshotDate?: string;
    };
    /** @description Line-based extraction of a PDF statement: one booking per line that recordStartPattern finds, its cells the capture groups of rowPattern, named by columns, and, with headerLabels, the words under each header label. */
    ImportPdfLayout: {
      /** @description Names of rowPattern's capture groups, in order; each at most 100 characters. */
      columns?: string[];
      /** @description RE2 pattern matched against a whole booking line; one group per column. */
      rowPattern?: string;
      /** @description Literal text every statement of this layout contains. */
      documentMarker?: string;
      /** @description RE2 pattern; a line on which it finds a match is a booking line. */
      recordStartPattern?: string;
      /** @description Optional (text layer only): the labels of the booking table's header line, in order. Each word of a booking line becomes the cell of the label above it. */
      headerLabels?: string[];
      /** @description Optional: the column that lines following a booking line, and no booking themselves, are appended to. */
      continuationColumn?: string;
      /** @description Optional RE2 pattern; a line on which it finds a match starts a section, whose first capture group is the sectionColumn cell of its bookings. */
      sectionPattern?: string;
      /** @description Optional: the column holding the section pattern's first group. */
      sectionColumn?: string;
      /** @description Optional RE2 pattern; a line on which it finds a match states a balance (its first capture group) and is never a booking. Include carry-forward lines at page breaks, or they continue the page's last booking. */
      balanceLinePattern?: string;
      /** @description Optional: the column holding each booking's running balance; every booking is then checked against the balance before it. */
      balanceColumn?: string;
      /** @description Optional RE2 pattern; a line on which it finds a match ends the booking above it, and no line continues a booking until the next booking line. */
      continuationEndPattern?: string;
      /** @description Optional (with headerLabels): the label of free text that may run on under the next labels; a word following it at word spacing stays in it. */
      flowColumn?: string;
    };
    /** @description Line-based extraction of a PDF statement: one booking per line that recordStartPattern finds, its cells the capture groups of rowPattern, named by columns, and, with headerLabels, the words under each header label. */
    ImportPdfLayoutResponse: {
      /** @description Names of rowPattern's capture groups, in order; each at most 100 characters. */
      columns: string[];
      /** @description RE2 pattern matched against a whole booking line; one group per column. */
      rowPattern: string;
      /** @description Literal text every statement of this layout contains. */
      documentMarker: string;
      /** @description RE2 pattern; a line on which it finds a match is a booking line. */
      recordStartPattern: string;
      /** @description Optional (text layer only): the labels of the booking table's header line, in order. Each word of a booking line becomes the cell of the label above it. */
      headerLabels: string[];
      /** @description Optional: the column that lines following a booking line, and no booking themselves, are appended to. */
      continuationColumn: string | null;
      /** @description Optional RE2 pattern; a line on which it finds a match starts a section, whose first capture group is the sectionColumn cell of its bookings. */
      sectionPattern: string | null;
      /** @description Optional: the column holding the section pattern's first group. */
      sectionColumn: string | null;
      /** @description Optional RE2 pattern; a line on which it finds a match states a balance (its first capture group) and is never a booking. Include carry-forward lines at page breaks, or they continue the page's last booking. */
      balanceLinePattern: string | null;
      /** @description Optional: the column holding each booking's running balance; every booking is then checked against the balance before it. */
      balanceColumn: string | null;
      /** @description Optional RE2 pattern; a line on which it finds a match ends the booking above it, and no line continues a booking until the next booking line. */
      continuationEndPattern: string | null;
      /** @description Optional (with headerLabels): the label of free text that may run on under the next labels; a word following it at word spacing stays in it. */
      flowColumn: string | null;
    };
    ImportPreviewRowResponse: {
      /** Format: int32 */
      rowNumber: number;
      status: string;
      rawData: {
        [key: string]: string;
      };
      canonical: components['schemas']['CanonicalImportRow'] | null;
      /** @description Why the row is not imported: for an ERROR row the error, for a SKIPPED row the reason it is skipped (e.g. IMPORT_ROW_ZERO_AMOUNT). Null otherwise. */
      error: components['schemas']['ImportRowErrorResponse'] | null;
      /** @description Warnings a PARSED row carries whatever account it is imported into (e.g. IMPORT_ROW_VALUE_DATE_ADJUSTED); it is still imported. Empty otherwise. */
      warnings: components['schemas']['ImportRowErrorResponse'][];
    };
    ImportRollbackModifiedResponse: {
      /** Format: uuid */
      transactionId: string;
      reasons: string[];
    };
    ImportRollbackRequest: {
      reason?: string;
    };
    ImportRollbackResponse: {
      batch: components['schemas']['ImportBatchResponse'];
      rollback: string;
      /** Format: int32 */
      deletedTransactionCount: number;
      modified: components['schemas']['ImportRollbackModifiedResponse'][];
      voidedTransactionIds: string[];
      reversalTransactionIds: string[];
      unmatchedTransactionIds: string[];
    };
    ImportRowErrorResponse: {
      /** @description Stable row code; the text is tmw.import.row.<code> with args in order (docs/api/error-codes.md). An open set: a client shows message for a code it does not know. Existing codes are never renamed, and removed only as a declared breaking change. */
      code: string;
      args: {
        [key: string]: string;
      };
      message: string;
    };
    ImportRowFilter: {
      /** @description Source column name or 0-based column index, like other mappings. */
      column?: string;
      /** @description Accepted values, compared after trimming and ignoring case. */
      acceptedValues?: string[];
    };
    ImportRowFilterResponse: {
      /** @description Source column name or 0-based column index, like other mappings. */
      column: string;
      /** @description Accepted values, compared after trimming and ignoring case. */
      acceptedValues: string[];
    };
    ImportRowInclusionRequest: {
      included: boolean;
    };
    ImportRowResponse: {
      /** Format: int32 */
      rowNumber: number;
      status: string;
      included: boolean;
      rawData: {
        [key: string]: string;
      };
      canonical: components['schemas']['CanonicalImportRow'] | null;
      /** Format: uuid */
      duplicateOfTransactionId: string | null;
      /** @description For a row with a duplicateOfTransactionId, that ledger row: its booking date, amount, currency and description. The duplicate rule compares booking date, amount and currency only (US-07-17), so the descriptions may differ; the member can force the row in. Null exactly when duplicateOfTransactionId is. */
      duplicateOf: components['schemas']['ImportDuplicateOfResponse'] | null;
      /** @description Why the row is not imported: for an ERROR row the error, for a SKIPPED row the reason it is skipped (e.g. IMPORT_ROW_ZERO_AMOUNT). Null otherwise. */
      error: components['schemas']['ImportRowErrorResponse'] | null;
      warnings: components['schemas']['ImportRowErrorResponse'][];
      /** Format: uuid */
      resultingTransactionId: string | null;
      /**
       * @description Set while the batch is PARSED for a row its commit would now treat otherwise than the preview stored it (#308), as the ledger has changed since: NEW, a duplicate whose booking is gone, imported as a new row; REPOINTED, a duplicate whose booking another ledger row holds now (a correction, a restored void); DUPLICATE, a new row whose booking the ledger has gained. status, included, duplicateOfTransactionId and duplicateOf already show what the commit will do. Null for every other row.
       * @enum {string|null}
       */
      recheck: 'NEW' | 'REPOINTED' | 'DUPLICATE' | null;
    };
    ImportSameFileResponse: {
      /** Format: uuid */
      batchId: string;
      /** Format: date-time */
      committedAt: string;
    };
    ImportStatementBalanceResponse: {
      /** @enum {string} */
      outcome: 'FOUND' | 'OUT_OF_RANGE' | 'NOT_FOUND' | 'UNREADABLE' | 'AMBIGUOUS';
      /** Format: date */
      date: string | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      balance: DecimalString | null;
      currency: string | null;
      /** Format: date */
      openingDate: string | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      openingBalance: DecimalString | null;
      /** @enum {string|null} */
      snapshot:
        | 'NEW'
        | 'COMPLETES'
        | 'ALREADY_RECORDED'
        | 'CONFLICTING'
        | 'RECORDED'
        | 'COMPLETED'
        | 'DELETED'
        | 'WITHDRAWN'
        | 'REMOVED'
        | null;
      existingSnapshot: components['schemas']['Existing'] | null;
      /** @enum {array} */
      adds: 'BALANCE';
      conflicts: components['schemas']['Conflict'][];
      explanation: string | null;
    };
    ImportStatementBalanceRule: {
      /** @description RE2 pattern with the named groups date and amount, and optionally currency and sign, e.g. Kontostand vom (?P<date>\S+): (?P<amount>\S+). A statement with sections in several currencies needs the currency group: without it, every balance is taken to be in the account's currency. The date group may be left out when statementDatePattern gives the date. */
      pattern?: string;
      /** @description Optional: the date's format, if not the template's date format. */
      dateFormat?: string;
      /** @description Optional RE2 pattern with the one named group date, e.g. Abrechnung vom \S+ bis (?P<date>\S+): its first match in the file with a readable date dates every balance line that has no date of its own. */
      statementDatePattern?: string;
      /** @description Optional: the format of statementDatePattern's date, if not the balance lines'. */
      statementDateFormat?: string;
    };
    ImportStatementBalanceRuleResponse: {
      /** @description RE2 pattern with the named groups date and amount, and optionally currency and sign, e.g. Kontostand vom (?P<date>\S+): (?P<amount>\S+). A statement with sections in several currencies needs the currency group: without it, every balance is taken to be in the account's currency. The date group may be left out when statementDatePattern gives the date. */
      pattern: string;
      /** @description Optional: the date's format, if not the template's date format. */
      dateFormat: string | null;
      /** @description Optional RE2 pattern with the one named group date, e.g. Abrechnung vom \S+ bis (?P<date>\S+): its first match in the file with a readable date dates every balance line that has no date of its own. */
      statementDatePattern: string | null;
      /** @description Optional: the format of statementDatePattern's date, if not the balance lines'. */
      statementDateFormat: string | null;
    };
    ImportTemplateCandidateResponse: {
      template: components['schemas']['ImportTemplateResponse'];
      match: string;
    };
    ImportTemplateRequest: {
      name: string;
      /** Format: uuid */
      institutionCatalogueId?: string;
      templateClass?: string;
      delimiter?: string;
      /** @enum {string} */
      encoding?: 'UTF-8' | 'ISO-8859-1' | 'windows-1252';
      decimalSeparator?: string;
      thousandsSeparator?: string;
      /** @description java.time pattern, e.g. dd.MM.uuuu (yyyy is accepted as well). */
      dateFormat?: string;
      /** Format: int32 */
      headerRowIndex?: number;
      /** Format: int32 */
      preambleRowCount?: number;
      /** Format: int32 */
      trailingSummaryRowCount?: number;
      amountRepresentation?: string;
      currencyMode?: string;
      fixedCurrency?: string;
      columnMapping: components['schemas']['ImportColumnMapping'];
      typeMapping?: {
        [key: string]: string;
      };
      accountIdentificationStrategy?: string;
      headerColumns?: string[];
      fileFormat?: string;
      pdfLayout?: components['schemas']['ImportPdfLayout'];
      descriptionTypePatterns?: {
        [key: string]: string;
      };
      rowFilter?: components['schemas']['ImportRowFilter'];
      accountIdentifier?: components['schemas']['ImportAccountIdentifierRule'];
      statementBalance?: components['schemas']['ImportStatementBalanceRule'];
      holdingsRule?: components['schemas']['ImportHoldingsRule'];
      /** @description US-07-22: RE2 pattern (matched ignoring case) for the description of a move between the account and its savings pocket. Such a row is a TRANSFER, and its commit books the other leg on the pocket account. */
      savingsPocketPattern?: string;
    };
    ImportTemplateResponse: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      familyId: string;
      name: string;
      /** Format: uuid */
      institutionCatalogueId: string | null;
      templateClass: string;
      templateVersion: string;
      /** Format: date */
      effectiveFrom: string;
      current: boolean;
      active: boolean;
      systemProvided: boolean;
      canEdit: boolean;
      delimiter: string;
      encoding: string;
      decimalSeparator: string;
      thousandsSeparator: string | null;
      dateFormat: string;
      /** Format: int32 */
      headerRowIndex: number;
      /** Format: int32 */
      preambleRowCount: number;
      /** Format: int32 */
      trailingSummaryRowCount: number;
      amountRepresentation: string;
      currencyMode: string;
      fixedCurrency: string | null;
      columnMapping: components['schemas']['ImportColumnMappingResponse'];
      typeMapping: {
        [key: string]: string;
      };
      accountIdentificationStrategy: string;
      headerColumns: string[] | null;
      headerFingerprint: string | null;
      fileFormat: string;
      pdfLayout: components['schemas']['ImportPdfLayoutResponse'] | null;
      /** Format: int32 */
      version: number;
      descriptionTypePatterns: {
        [key: string]: string;
      };
      rowFilter: components['schemas']['ImportRowFilterResponse'] | null;
      accountIdentifier: components['schemas']['ImportAccountIdentifierRuleResponse'] | null;
      statementBalance: components['schemas']['ImportStatementBalanceRuleResponse'] | null;
      holdingsRule: components['schemas']['ImportHoldingsRuleResponse'] | null;
      savingsPocketPattern: string | null;
    };
    ImportTemplateTestResponse: {
      headerColumns: string[];
      headerFingerprint: string | null;
      /** Format: int32 */
      rowCount: number;
      /** Format: int32 */
      parsedRowCount: number;
      /** Format: int32 */
      errorRowCount: number;
      /** Format: int32 */
      skippedRowCount: number;
      errorCounts: {
        [key: string]: number;
      };
      rows: components['schemas']['ImportPreviewRowResponse'][];
      accountIdentifierFound: boolean | null;
      /** Format: int32 */
      statementBalanceCount: number | null;
      /** Format: int32 */
      statementBalanceUnreadableCount: number | null;
    };
    InstitutionCatalogueEntrySummaryResponse: {
      /** Format: uuid */
      id: string;
      name: string;
      country: string;
      institutionType: string;
      identifier: string | null;
      logoUrl: string | null;
      /** @description US-04-10: the file formats a statement of this institution can be imported in, sorted; empty when it has to be entered by hand. */
      importFormats: ('CAMT' | 'CSV' | 'PDF' | 'SPREADSHEET')[];
    };
    LastImport: {
      /** Format: date-time */
      uploadedAt: string;
      fileFormat: string;
      /** Format: int32 */
      transactionCount: number;
    };
    LinkSavingsPocketRequest: {
      /** Format: uuid */
      savingsPocketAccountId: string;
    };
    LoanRepaymentResponse: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      loanAccountId: string;
      /** Format: uuid */
      payingAccountId: string;
      /** Format: date */
      bookingDate: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      amount: DecimalString;
      currency: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      interestAmount: DecimalString | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      principalAmount: DecimalString | null;
      splitSource: string;
      splitEstimated: boolean;
      /** Format: uuid */
      amortisationScheduleEntryId: string | null;
      externalId: string | null;
      transactions: components['schemas']['TransactionResponse'][];
      /** Format: date-time */
      createdAt: string;
    };
    LoginRequest: {
      email: string;
      password: string;
    };
    LoginResponse: {
      mfaRequired: boolean;
      mfaChallengeToken: string | null;
      tokens: components['schemas']['AuthTokensResponse'] | null;
    };
    MfaConfirmRequest: {
      code: string;
    };
    MfaDisableRequest: {
      password: string;
      code?: string;
    };
    MfaEnrollmentRequest: {
      password: string;
    };
    MfaEnrollmentResponse: {
      secret: string;
      otpauthUri: string;
    };
    MfaVerifyRequest: {
      challengeToken: string;
      code: string;
    };
    NetWorthResponse: {
      currency: string;
      /** Format: date */
      asOf: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      totalAssets: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      totalLiabilities: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      netWorth: DecimalString;
      complete: boolean;
      approximate: boolean;
      warnings: string[];
      accounts: components['schemas']['AccountValuation'][];
    };
    OpeningBalanceRequest: {
      /** Format: date */
      date: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      balance: DecimalString;
      currency: string;
      acknowledgeEarlierTransactions?: boolean;
    };
    OpeningBalanceResponse: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      accountId: string;
      /** Format: date */
      date: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      balance: DecimalString;
      currency: string;
      /** Format: date-time */
      createdAt: string;
      /** Format: date-time */
      updatedAt: string | null;
      /** Format: int32 */
      version: number;
      warnings: string[];
    };
    PageMetadata: {
      /** Format: int32 */
      number: number;
      /** Format: int32 */
      size: number;
      /** Format: int64 */
      totalElements: number;
      /** Format: int32 */
      totalPages: number;
    };
    PageResponseAccountSnapshotResponse: {
      content: components['schemas']['AccountSnapshotResponse'][];
      page: components['schemas']['PageMetadata'];
    };
    PageResponseAmortisationScheduleEntryResponse: {
      content: components['schemas']['AmortisationScheduleEntryResponse'][];
      page: components['schemas']['PageMetadata'];
    };
    PageResponseCustomAssetValuationResponse: {
      content: components['schemas']['CustomAssetValuationResponse'][];
      page: components['schemas']['PageMetadata'];
    };
    PageResponseImportBatchResponse: {
      content: components['schemas']['ImportBatchResponse'][];
      page: components['schemas']['PageMetadata'];
    };
    PageResponseImportRowResponse: {
      content: components['schemas']['ImportRowResponse'][];
      page: components['schemas']['PageMetadata'];
    };
    PageResponseInstitutionCatalogueEntrySummaryResponse: {
      content: components['schemas']['InstitutionCatalogueEntrySummaryResponse'][];
      page: components['schemas']['PageMetadata'];
    };
    PageResponseLoanRepaymentResponse: {
      content: components['schemas']['LoanRepaymentResponse'][];
      page: components['schemas']['PageMetadata'];
    };
    PageResponseReconciliationResultResponse: {
      content: components['schemas']['ReconciliationResultResponse'][];
      page: components['schemas']['PageMetadata'];
    };
    PageResponseSettlementMatchResponse: {
      content: components['schemas']['SettlementMatchResponse'][];
      page: components['schemas']['PageMetadata'];
    };
    PageResponseTransactionResponse: {
      content: components['schemas']['TransactionResponse'][];
      page: components['schemas']['PageMetadata'];
    };
    PortfolioListItemResponse: {
      /** Format: uuid */
      id: string;
      /** Format: int32 */
      version: number;
      name: string;
      /** Format: uuid */
      catalogueInstitutionId: string | null;
      institutionType: string;
      institutionName: string | null;
      country: string | null;
      logoUrl: string | null;
      /** @enum {string} */
      portfolioType:
        | 'BANK'
        | 'PORTFOLIO'
        | 'PILLAR_3A'
        | 'PENSION_FUND'
        | 'CRYPTO'
        | 'PERSONAL_ASSETS'
        | 'MIXED';
      portfolioTypeOverridden: boolean;
      personalAssetsDefault: boolean;
      /** Format: int32 */
      activeAccountCount: number;
      portfolioCurrency: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      total: DecimalString;
      workspaceCurrency: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      totalInWorkspaceCurrency: DecimalString;
      complete: boolean;
      /** @enum {string} */
      valueStatus: 'CURRENT' | 'ESTIMATED' | 'STALE' | 'UNRECONCILED' | 'UNKNOWN';
      /** Format: date */
      asOf: string;
    };
    PortfolioResponse: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      catalogueInstitutionId: string | null;
      name: string;
      country: string | null;
      institutionType: string;
      identifier: string | null;
      logoUrl: string | null;
      portfolioCurrency: string;
      /** @enum {string} */
      portfolioType:
        | 'BANK'
        | 'PORTFOLIO'
        | 'PILLAR_3A'
        | 'PENSION_FUND'
        | 'CRYPTO'
        | 'PERSONAL_ASSETS'
        | 'MIXED';
      portfolioTypeOverridden: boolean;
      personalAssetsDefault: boolean;
      status: string;
      /** Format: int32 */
      version: number;
    };
    PortfolioSummaryResponse: {
      /** Format: uuid */
      institutionId: string;
      portfolioCurrency: string;
      currency: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      totalAssets: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      totalLiabilities: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      netValue: DecimalString;
      complete: boolean;
      warnings: string[];
      accounts: components['schemas']['AccountContribution'][];
    };
    PortfolioViewResponse: {
      /** Format: uuid */
      portfolioId: string;
      name: string;
      /** @enum {string} */
      portfolioType:
        | 'BANK'
        | 'PORTFOLIO'
        | 'PILLAR_3A'
        | 'PENSION_FUND'
        | 'CRYPTO'
        | 'PERSONAL_ASSETS'
        | 'MIXED';
      portfolioTypeOverridden: boolean;
      personalAssetsDefault: boolean;
      portfolioCurrency: string;
      workspaceCurrency: string;
      /** Format: date */
      asOf: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      total: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      totalInWorkspaceCurrency: DecimalString;
      complete: boolean;
      /** @enum {string} */
      valueStatus: 'CURRENT' | 'ESTIMATED' | 'STALE' | 'UNRECONCILED' | 'UNKNOWN';
      warnings: string[];
      sections: components['schemas']['Section'][];
      institutionName: string | null;
      logoUrl: string | null;
      /** Format: decimal */
      conversionRate: DecimalString | null;
      /** Format: date */
      conversionRateDate: string | null;
      lastImport: components['schemas']['LastImport'] | null;
    };
    Position: {
      /** Format: int32 */
      rowNumber: number;
      isin: string | null;
      symbol: string | null;
      name: string | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      quantity: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      reportedPrice: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      reportedValue: DecimalString;
      reportedCurrency: string;
      /** @enum {string} */
      resolution: 'RESOLVED' | 'UNRESOLVED';
      /** Format: uuid */
      securityId: string | null;
      warnings: components['schemas']['ImportRowErrorResponse'][];
    };
    ReassignAccountPortfolioRequest: {
      /** Format: uuid */
      portfolioId: string;
    };
    ReconciliationDecisionRequest: {
      note: string;
    };
    ReconciliationResultResponse: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      accountId: string;
      /** Format: uuid */
      snapshotId: string;
      /** Format: date */
      asOf: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      differenceAmount: DecimalString | null;
      currency: string;
      probableCause: string | null;
      status: string;
      /** Format: date-time */
      resolvedAt: string | null;
      /** Format: date-time */
      createdAt: string;
      resolutionNote: string | null;
      /** Format: uuid */
      resolutionTransactionId: string | null;
      /** Format: int32 */
      version: number;
      finalized: boolean;
    };
    ReconciliationStatusResponse: {
      status: string;
      reason: string | null;
      /** Format: date */
      asOf: string | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      openDifference: DecimalString | null;
      currency: string | null;
    };
    RecordAccountSnapshotRequest: {
      /** Format: date */
      snapshotDate: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      balance?: DecimalString;
      holdings?: components['schemas']['SnapshotHoldingRequest'][];
    };
    ReferenceDataResponse: {
      packageVersion: string;
      /** Format: date */
      publicationDate: string;
      /** Format: date-time */
      importedAt: string;
      /** Format: uuid */
      importedBy: string | null;
      contents: components['schemas']['Contents'];
      stalenessWarnings: components['schemas']['StalenessWarning'][];
    };
    RefreshTokenRequest: {
      refreshToken: string;
    };
    ReplaceAccountSnapshotRequest: {
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      balance?: DecimalString;
      holdings?: components['schemas']['SnapshotHoldingRequest'][];
    };
    Section: {
      /** @enum {string} */
      section:
        | 'CASH_AND_SAVINGS'
        | 'PILLAR_3A'
        | 'PENSION'
        | 'DEPOT'
        | 'CRYPTO'
        | 'CARDS'
        | 'PROPERTY_AND_MORTGAGE'
        | 'OTHER';
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      subtotal: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      subtotalInWorkspaceCurrency: DecimalString;
      complete: boolean;
      /** @enum {string} */
      valueStatus: 'CURRENT' | 'ESTIMATED' | 'STALE' | 'UNRECONCILED' | 'UNKNOWN';
      accounts: components['schemas']['AccountRow'][];
    };
    SecurityCompleteness: {
      complete: boolean;
      missingFields: string[];
    };
    SecurityResponse: {
      /** Format: uuid */
      id: string;
      isin: string | null;
      syntheticKey: string | null;
      displayName: string;
      legalName: string;
      denominationCurrency: string;
      instrumentType: string | null;
      assetClass: string | null;
      securityCountry: string | null;
      issuerCountry: string | null;
      state: string;
      completeness: components['schemas']['SecurityCompleteness'];
    };
    SessionSummaryResponse: {
      /** Format: uuid */
      id: string;
      deviceLabel: string | null;
      status: string;
      /** Format: date-time */
      createdAt: string;
      /** Format: date-time */
      lastSeenAt: string;
      current: boolean;
    };
    SetSettlementSourceRequest: {
      /** Format: uuid */
      settlementSourceAccountId?: string;
    };
    SetStatementConfigRequest: {
      /** Format: int32 */
      statementDay?: number;
      /** Format: int32 */
      dueDateOffsetDays?: number;
    };
    SetTransactionCategoryRequest: {
      /** Format: uuid */
      categoryId: string;
    };
    SettlementMatchResponse: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      cardAccountId: string;
      /** Format: uuid */
      paymentAccountId: string;
      /** Format: uuid */
      paymentTransactionId: string;
      /** Format: uuid */
      cardTransactionId: string | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      amount: DecimalString;
      currency: string;
      /** Format: date */
      paymentBookingDate: string;
      status: string;
      matchBasis: string;
      /** Format: date-time */
      decidedAt: string | null;
      /** Format: date-time */
      createdAt: string;
      matchKind: string;
      /** Format: uuid */
      debitAccountId: string;
      /** Format: uuid */
      debitTransactionId: string;
      /** Format: uuid */
      creditAccountId: string;
      /** Format: uuid */
      creditTransactionId: string | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      transferFxRate: DecimalString | null;
      /** Format: int32 */
      version: number;
    };
    SettlementMatchRunResponse: {
      /** Format: int32 */
      newlyProposed: number;
      /** Format: int32 */
      newlyConfirmed: number;
    };
    SettlementSourceResponse: {
      /** Format: uuid */
      cardAccountId: string;
      /** Format: uuid */
      settlementSourceAccountId: string | null;
      /** Format: int32 */
      version: number;
    };
    SetupAdministratorRequest: {
      /** Format: email */
      email: string;
      password: string;
      workspaceName?: string;
      currencyCode: string;
    };
    SnapshotHoldingRequest: {
      /** Format: uuid */
      securityId: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      quantity: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      reportedCostBasis?: DecimalString;
      costBasisIsEstimated?: boolean;
    };
    SnapshotHoldingResponse: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      securityId: string;
      isin: string | null;
      securityDisplayName: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      quantity: DecimalString;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      reportedCostBasis: DecimalString | null;
      costBasisIsEstimated: boolean;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      reportedPrice: DecimalString | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      reportedValue: DecimalString | null;
      reportedCurrency: string | null;
    };
    StalenessWarning: {
      code: string;
      subject: string;
      missingPeriod: string;
    };
    StatementConfigResponse: {
      /** Format: uuid */
      cardAccountId: string;
      /** Format: int32 */
      statementDay: number | null;
      /** Format: int32 */
      dueDateOffsetDays: number | null;
      /** Format: int32 */
      version: number;
    };
    TransactionCorrectionResponse: {
      /** Format: int32 */
      version: number;
      transaction: components['schemas']['TransactionResponse'];
      removal: components['schemas']['TransactionRemovalResponse'] | null;
    };
    TransactionRemovalResponse: {
      removal: string;
      /** Format: int32 */
      version: number;
      affected: components['schemas']['TransactionResponse'][];
      reversals: components['schemas']['TransactionResponse'][];
      unmatchedTransactionIds: string[];
      restored: components['schemas']['TransactionResponse'][];
    };
    TransactionResponse: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      accountId: string;
      accountName: string;
      transactionType: string;
      /** Format: date */
      bookingDate: string;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      amount: DecimalString;
      currency: string;
      merchantDescription: string | null;
      counterpartyName: string | null;
      mcc: string | null;
      notes: string | null;
      source: string;
      externalId: string | null;
      internalTransfer: boolean;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      fxRateToAccountCurrency: DecimalString | null;
      fxRateEstimated: boolean;
      /** Format: uuid */
      relatedTransactionId: string | null;
      /** Format: date-time */
      createdAt: string;
      /** Format: uuid */
      securityId: string | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      quantity: DecimalString | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      unitPrice: DecimalString | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      feeAmount: DecimalString | null;
      /** Format: date */
      tradeDate: string | null;
      /** Format: date */
      settlementDate: string | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      grossAmount: DecimalString | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      taxWithheldAmount: DecimalString | null;
      /**
       * Format: decimal
       * @description Exact decimal encoded as a plain JSON string. Strings are canonical on requests; JSON numbers remain accepted for compatibility.
       */
      netAmount: DecimalString | null;
      /** Format: uuid */
      categoryId: string | null;
      categoryAssignedBy: string | null;
      removal: string | null;
      reconciliationAdjustment: string | null;
      /** Format: date-time */
      voidedAt: string | null;
      voidReason: string | null;
      /** Format: uuid */
      replacesTransactionId: string | null;
      /** Format: uuid */
      correctsTransactionId: string | null;
      /** Format: uuid */
      restoresTransactionId: string | null;
      /** Format: date-time */
      deletedAt: string | null;
      /** Format: uuid */
      counterpartyAccountId: string | null;
      /** Format: int32 */
      version: number;
      warnings: string[];
    };
    Unreadable: {
      /** Format: int32 */
      rowNumber: number;
      line: string;
      error: components['schemas']['ImportRowErrorResponse'];
    };
    UpdateAccountRequest: {
      name: string;
      accountType: string;
      nativeCurrency: string;
      jurisdiction?: string;
      /** Format: date */
      openedAt?: string;
      /** Format: date */
      closedAt?: string;
    };
    UpdateCategoryRequest: {
      /** Format: uuid */
      parentId?: string;
      nameEn: string;
      nameDe: string;
    };
    UpdateFxImportIntervalRequest: {
      /**
       * Format: int32
       * @enum {integer}
       */
      intervalHours: '1' | '2' | '6' | '12' | '24';
    };
    UpdatePortfolioRequest: {
      name: string;
      country?: string;
      logoUrl?: string;
      portfolioType: string;
    };
    UpdateWorkspaceCurrencyRequest: {
      currency: string;
    };
    UpdateWorkspaceNameRequest: {
      name: string;
    };
    UserSummaryResponse: {
      /** Format: uuid */
      id: string;
      email: string;
      role: string;
      status: string;
      language: string;
      /** Format: int32 */
      version: number;
    };
    WorkspaceMemberListItemResponse: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      userId: string;
      displayName: string;
      active: boolean;
      /** Format: int32 */
      version: number;
    };
    WorkspaceMemberSummaryResponse: {
      /** Format: uuid */
      id: string;
      displayName: string;
      status: string;
      /** Format: date */
      memberSince: string;
      /** Format: date */
      memberUntil: string | null;
      /** Format: int32 */
      version: number;
    };
    WorkspaceResponse: {
      /** Format: uuid */
      id: string;
      name: string | null;
      currency: string;
      /** Format: int32 */
      version: number;
    };
  };
  responses: {
    /** @description Error response using the TrackMyWealth problem-detail contract. */
    ApiProblemResponse: {
      headers: {
        /** @description Correlation id assigned to this request. */
        'X-Correlation-Id'?: string;
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ApiProblem'];
      };
    };
  };
  parameters: never;
  requestBodies: never;
  headers: {
    /** @description Strong ETag of the returned resource's version, for example "7". Send it as If-Match to change or delete this version of the resource. */
    ETag: string;
    /** @description URL of the created resource. */
    Location: string;
  };
  pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
  listAccounts: {
    parameters: {
      query?: {
        portfolioId?: string;
        accountType?: string;
        archived?: boolean;
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountListItemResponse'][];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  createAccount: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['CreateAccountRequest'];
      };
    };
    responses: {
      /** @description Created */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountSummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getAccount: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountSummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  updateAccount: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['UpdateAccountRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountSummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMMUTABLE_FIELD`: A field that never changes after creation, such as an account's type or currency.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listAmortisationEntries: {
    parameters: {
      query?: {
        /** @description Zero-based page number. */
        page?: number;
        /** @description Items per page; a larger value is capped to 200, and page.size in the response says the size applied. */
        size?: number;
        /** @description Repeatable <field>[,asc|desc]; any other field is a 400 INVALID_SORT. Default: dueDate,asc. The order always ends with a unique tie-breaker, so pages never overlap. */
        sort?: ('dueDate' | 'dueDate,asc' | 'dueDate,desc')[];
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PageResponseAmortisationScheduleEntryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       *     - `INVALID_SORT`: A paged list was asked to sort by a field it does not offer, or in a malformed way; detail names the field and the fields the list sorts by.
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  createAmortisationEntry: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['AmortisationScheduleEntryRequest'];
      };
    };
    responses: {
      /** @description Created */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AmortisationScheduleEntryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       *     - `ACCOUNT_ARCHIVED`: The account is archived; restore it first.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  replaceAmortisationEntry: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        entryId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['AmortisationScheduleEntryRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AmortisationScheduleEntryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `ACCOUNT_ARCHIVED`: The account is archived; restore it first.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  deleteAmortisationEntry: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        entryId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description No Content */
      204: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content?: never;
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `ACCOUNT_ARCHIVED`: The account is archived; restore it first.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  archiveAccount: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountSummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getAccountBalance: {
    parameters: {
      query?: {
        asOf?: string;
        currency?: string;
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountValuation'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getAccountIdentifier: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountIdentifierResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  setAccountIdentifier: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['AccountIdentifierRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountIdentifierResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `ACCOUNT_IDENTIFIER_IN_USE`: Another account of the workspace is already registered with this identifier.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `ACCOUNT_IDENTIFIER_INVALID`: Not a valid account identifier for this account (IBAN check digits, masked card number).
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `ACCOUNT_IDENTIFICATION_UNAVAILABLE`: Account identification is switched off on this server (no key configured).
       */
      503: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  clearAccountIdentifier: {
    parameters: {
      query?: {
        kind?: string;
      };
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountIdentifierResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `ACCOUNT_IDENTIFIER_INVALID`: Not a valid account identifier for this account (IBAN check digits, masked card number).
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  setAccountIdentifierFromImport: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['AccountIdentifierFromImportRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountIdentifierResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_BATCH_STATE`: The import batch's status (status) does not allow this step; a retried commit gets this, never a second import.
       *     - `ACCOUNT_IDENTIFIER_IN_USE`: Another account of the workspace is already registered with this identifier.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_TEMPLATE_INVALID`: The import template breaks a rule; field names the template field, detail the rule.
       *     - `IMPORT_TEMPLATE_UNSUPPORTED`: A valid template this release cannot import yet.
       *     - `IMPORT_TEMPLATE_MISMATCH`: The file's header lacks columns the template maps (missingColumns); no row is parsed.
       *     - `IMPORT_ENCODING_SUSPECT`: The header holds characters the template's encoding cannot decode; the file is probably in another encoding.
       *     - `IMPORT_FILE_EMPTY`: The file is empty, or holds nothing after the skipped preamble.
       *     - `IMPORT_FILE_NO_DATA_ROWS`: The file has a header but no data row.
       *     - `IMPORT_FILE_MALFORMED`: The file is not valid CSV for the template's delimiter, or not a readable PDF, XML or spreadsheet (damaged, encrypted).
       *     - `IMPORT_FILE_TOO_MANY_ROWS`: The file has more data rows than one import may hold (maxRows).
       *     - `IMPORT_FILE_TOO_MANY_PAGES`: A PDF has more pages than one import may hold (maxPages).
       *     - `IMPORT_PDF_LIMIT_EXCEEDED`: A PDF holds more than one statement may (text, objects, images); exporting a shorter statement helps.
       *     - `IMPORT_PDF_NO_TEXT`: A scanned PDF without a text layer, but the template reads text; use an OCR template.
       *     - `IMPORT_OCR_FAILED`: Local OCR could not read a scanned page (it failed or produced too much text).
       *     - `ACCOUNT_IDENTIFIER_NOT_IN_FILE`: The import file names no account identifier the template can read, or one of another kind.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_PDF_BUSY`: The server is reading as many PDF statements as it may at once.
       *     - `IMPORT_OCR_UNAVAILABLE`: Local OCR is not installed, busy, or did not finish within the time limit.
       *     - `ACCOUNT_IDENTIFICATION_UNAVAILABLE`: Account identification is switched off on this server (no key configured).
       */
      503: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listImportBatches: {
    parameters: {
      query?: {
        /** @description Zero-based page number. */
        page?: number;
        /** @description Items per page; a larger value is capped to 200, and page.size in the response says the size applied. */
        size?: number;
        /** @description Repeatable <field>[,asc|desc]; any other field is a 400 INVALID_SORT. Default: uploadedAt,desc. The order always ends with a unique tie-breaker, so pages never overlap. */
        sort?: ('uploadedAt' | 'uploadedAt,asc' | 'uploadedAt,desc')[];
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PageResponseImportBatchResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       *     - `INVALID_SORT`: A paged list was asked to sort by a field it does not offer, or in a malformed way; detail names the field and the fields the list sorts by.
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  createImportBatch: {
    parameters: {
      query?: {
        templateId?: string;
        snapshotDate?: string;
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: {
      content: {
        'multipart/form-data': {
          /** Format: binary */
          file: string;
        };
      };
    };
    responses: {
      /** @description Created */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportBatchResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       *     - `ACCOUNT_ARCHIVED`: The account is archived; restore it first.
       *     - `IMPORT_TOO_MANY_OPEN_BATCHES`: The account holds as many open import batches as it may (maxOpenBatches); commit or discard one first.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_FILE_TOO_LARGE`: The uploaded file exceeds the 5 MB import limit.
       */
      413: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       *     - `IMPORT_TEMPLATE_INVALID`: The import template breaks a rule; field names the template field, detail the rule.
       *     - `IMPORT_TEMPLATE_UNSUPPORTED`: A valid template this release cannot import yet.
       *     - `IMPORT_TEMPLATE_MISMATCH`: The file's header lacks columns the template maps (missingColumns); no row is parsed.
       *     - `IMPORT_ENCODING_SUSPECT`: The header holds characters the template's encoding cannot decode; the file is probably in another encoding.
       *     - `IMPORT_FILE_EMPTY`: The file is empty, or holds nothing after the skipped preamble.
       *     - `IMPORT_FILE_NO_DATA_ROWS`: The file has a header but no data row.
       *     - `IMPORT_FILE_MALFORMED`: The file is not valid CSV for the template's delimiter, or not a readable PDF, XML or spreadsheet (damaged, encrypted).
       *     - `IMPORT_FILE_TOO_MANY_ROWS`: The file has more data rows than one import may hold (maxRows).
       *     - `IMPORT_CAMT_UNSUPPORTED`: An XML file is no supported ISO 20022 message: only camt.053 and camt.052, versions 001.02, 001.04 and 001.08.
       *     - `IMPORT_CAMT_LIMIT_EXCEEDED`: A CAMT file holds more than one import may; exporting a shorter period helps.
       *     - `IMPORT_CAMT_MULTIPLE_ACCOUNTS`: A CAMT file holds statements of more than one account; import each account's file on its own.
       *     - `IMPORT_FILE_TOO_MANY_PAGES`: A PDF has more pages than one import may hold (maxPages).
       *     - `IMPORT_PDF_LIMIT_EXCEEDED`: A PDF holds more than one statement may (text, objects, images); exporting a shorter statement helps.
       *     - `IMPORT_PDF_NO_TEXT`: A scanned PDF without a text layer, but the template reads text; use an OCR template.
       *     - `IMPORT_SPREADSHEET_LIMIT_EXCEEDED`: A spreadsheet holds more sheets, rows, cells or text than one import may; exporting less helps.
       *     - `IMPORT_OCR_FAILED`: Local OCR could not read a scanned page (it failed or produced too much text).
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_PDF_BUSY`: The server is reading as many PDF statements as it may at once.
       *     - `IMPORT_SPREADSHEET_BUSY`: The server is reading as many spreadsheets as it may at once.
       *     - `IMPORT_OCR_UNAVAILABLE`: Local OCR is not installed, busy, or did not finish within the time limit.
       *     - `ACCOUNT_IDENTIFICATION_UNAVAILABLE`: Account identification is switched off on this server (no key configured).
       */
      503: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getImportBatch: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        batchId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportBatchResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  proposeAccountFromImportBatch: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        batchId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportAccountProposalResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       *     - `IMPORT_BATCH_STATE`: The import batch's status (status) does not allow this step; a retried commit gets this, never a second import.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_TEMPLATE_INVALID`: The import template breaks a rule; field names the template field, detail the rule.
       *     - `IMPORT_TEMPLATE_UNSUPPORTED`: A valid template this release cannot import yet.
       *     - `IMPORT_TEMPLATE_MISMATCH`: The file's header lacks columns the template maps (missingColumns); no row is parsed.
       *     - `IMPORT_ENCODING_SUSPECT`: The header holds characters the template's encoding cannot decode; the file is probably in another encoding.
       *     - `IMPORT_FILE_EMPTY`: The file is empty, or holds nothing after the skipped preamble.
       *     - `IMPORT_FILE_NO_DATA_ROWS`: The file has a header but no data row.
       *     - `IMPORT_FILE_MALFORMED`: The file is not valid CSV for the template's delimiter, or not a readable PDF, XML or spreadsheet (damaged, encrypted).
       *     - `IMPORT_FILE_TOO_MANY_ROWS`: The file has more data rows than one import may hold (maxRows).
       *     - `IMPORT_FILE_TOO_MANY_PAGES`: A PDF has more pages than one import may hold (maxPages).
       *     - `IMPORT_PDF_LIMIT_EXCEEDED`: A PDF holds more than one statement may (text, objects, images); exporting a shorter statement helps.
       *     - `IMPORT_PDF_NO_TEXT`: A scanned PDF without a text layer, but the template reads text; use an OCR template.
       *     - `IMPORT_OCR_FAILED`: Local OCR could not read a scanned page (it failed or produced too much text).
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_PDF_BUSY`: The server is reading as many PDF statements as it may at once.
       *     - `IMPORT_OCR_UNAVAILABLE`: Local OCR is not installed, busy, or did not finish within the time limit.
       */
      503: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  commitImportBatch: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        batchId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportBatchResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `ACCOUNT_ARCHIVED`: The account is archived; restore it first.
       *     - `IMPORT_BATCH_STATE`: The import batch's status (status) does not allow this step; a retried commit gets this, never a second import.
       *     - `IMPORT_ACCOUNT_UNCONFIRMED`: The file's account identification needs the member's confirmation first (accountIdentification).
       *     - `IMPORT_SNAPSHOT_ALREADY_RECORDED`: The statement adds nothing to the imported snapshot of its account and date (existingSnapshotId); discard the import.
       *     - `IMPORT_SNAPSHOT_CONFLICT`: The statement differs from the imported snapshot of its account and date (existingSnapshotId, conflicts); nothing is overwritten.
       *     - `IMPORT_SNAPSHOT_RECONCILIATION_DECIDED`: The imported snapshot cannot change while a reconciliation decision on it stands (reconciliationResultId).
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       *     - `IMPORT_HOLDINGS_UNRESOLVED`: A holdings position resolves to no security (unresolved); create the security, then commit again.
       *     - `IMPORT_HOLDINGS_UNREADABLE`: A position or cash line of the holdings statement could not be read (rowNumbers); the template needs correcting.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  confirmImportBatchAccount: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        batchId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportBatchResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `ACCOUNT_ARCHIVED`: The account is archived; restore it first.
       *     - `IMPORT_BATCH_STATE`: The import batch's status (status) does not allow this step; a retried commit gets this, never a second import.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  discardImportBatch: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        batchId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportBatchResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `ACCOUNT_ARCHIVED`: The account is archived; restore it first.
       *     - `IMPORT_BATCH_STATE`: The import batch's status (status) does not allow this step; a retried commit gets this, never a second import.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getImportBatchErrors: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        batchId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description The rows that could not be imported, as CSV */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'text/csv': string;
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  parseImportBatch: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        batchId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['ImportParseRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportBatchResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `ACCOUNT_ARCHIVED`: The account is archived; restore it first.
       *     - `IMPORT_BATCH_STATE`: The import batch's status (status) does not allow this step; a retried commit gets this, never a second import.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       *     - `IMPORT_TEMPLATE_INVALID`: The import template breaks a rule; field names the template field, detail the rule.
       *     - `IMPORT_TEMPLATE_UNSUPPORTED`: A valid template this release cannot import yet.
       *     - `IMPORT_TEMPLATE_MISMATCH`: The file's header lacks columns the template maps (missingColumns); no row is parsed.
       *     - `IMPORT_ENCODING_SUSPECT`: The header holds characters the template's encoding cannot decode; the file is probably in another encoding.
       *     - `IMPORT_FILE_EMPTY`: The file is empty, or holds nothing after the skipped preamble.
       *     - `IMPORT_FILE_NO_DATA_ROWS`: The file has a header but no data row.
       *     - `IMPORT_FILE_MALFORMED`: The file is not valid CSV for the template's delimiter, or not a readable PDF, XML or spreadsheet (damaged, encrypted).
       *     - `IMPORT_FILE_TOO_MANY_ROWS`: The file has more data rows than one import may hold (maxRows).
       *     - `IMPORT_CAMT_UNSUPPORTED`: An XML file is no supported ISO 20022 message: only camt.053 and camt.052, versions 001.02, 001.04 and 001.08.
       *     - `IMPORT_CAMT_LIMIT_EXCEEDED`: A CAMT file holds more than one import may; exporting a shorter period helps.
       *     - `IMPORT_CAMT_MULTIPLE_ACCOUNTS`: A CAMT file holds statements of more than one account; import each account's file on its own.
       *     - `IMPORT_FILE_TOO_MANY_PAGES`: A PDF has more pages than one import may hold (maxPages).
       *     - `IMPORT_PDF_LIMIT_EXCEEDED`: A PDF holds more than one statement may (text, objects, images); exporting a shorter statement helps.
       *     - `IMPORT_PDF_NO_TEXT`: A scanned PDF without a text layer, but the template reads text; use an OCR template.
       *     - `IMPORT_SPREADSHEET_LIMIT_EXCEEDED`: A spreadsheet holds more sheets, rows, cells or text than one import may; exporting less helps.
       *     - `IMPORT_OCR_FAILED`: Local OCR could not read a scanned page (it failed or produced too much text).
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_PDF_BUSY`: The server is reading as many PDF statements as it may at once.
       *     - `IMPORT_SPREADSHEET_BUSY`: The server is reading as many spreadsheets as it may at once.
       *     - `IMPORT_OCR_UNAVAILABLE`: Local OCR is not installed, busy, or did not finish within the time limit.
       *     - `ACCOUNT_IDENTIFICATION_UNAVAILABLE`: Account identification is switched off on this server (no key configured).
       */
      503: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  rollbackImportBatch: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        batchId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['ImportRollbackRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportRollbackResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `ACCOUNT_ARCHIVED`: The account is archived; restore it first.
       *     - `IMPORT_BATCH_STATE`: The import batch's status (status) does not allow this step; a retried commit gets this, never a second import.
       *     - `IMPORT_ROLLBACK_RECONCILIATION_DECIDED`: The batch cannot be rolled back while a reconciliation decision on its statement balance stands (reconciliationResultId); reopen it first.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listImportBatchRows: {
    parameters: {
      query?: {
        status?: string;
        /** @description Zero-based page number. */
        page?: number;
        /** @description Items per page; a larger value is capped to 200, and page.size in the response says the size applied. */
        size?: number;
        /** @description Repeatable <field>[,asc|desc]; any other field is a 400 INVALID_SORT. Default: rowNumber,asc. The order always ends with a unique tie-breaker, so pages never overlap. */
        sort?: ('rowNumber' | 'rowNumber,asc' | 'rowNumber,desc')[];
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        batchId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PageResponseImportRowResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       *     - `INVALID_SORT`: A paged list was asked to sort by a field it does not offer, or in a malformed way; detail names the field and the fields the list sorts by.
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  setImportRowIncluded: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        batchId: string;
        rowNumber: number;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['ImportRowInclusionRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportBatchResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `ACCOUNT_ARCHIVED`: The account is archived; restore it first.
       *     - `IMPORT_BATCH_STATE`: The import batch's status (status) does not allow this step; a retried commit gets this, never a second import.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       *     - `IMPORT_ROW_NOT_INCLUDABLE`: An import row with status ERROR cannot be included in the commit.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getOpeningBalance: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OpeningBalanceResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       *     - `OPENING_BALANCE_NOT_RECORDED`: The account has no opening balance yet; record one with POST.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  replaceOpeningBalance: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['OpeningBalanceRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OpeningBalanceResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       *     - `OPENING_BALANCE_NOT_RECORDED`: The account has no opening balance yet; record one with POST.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `OPENING_BALANCE_AFTER_FIRST_TRANSACTION`: Live transactions are booked before the opening date (transactionCount, earliestBookingDate); move the date back or repeat with acknowledgeEarlierTransactions = true.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       *     - `OPENING_BALANCE_NOT_APPLICABLE`: This account takes no opening balance: a loan, a mortgage or a manually valued asset has a value source of its own.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  recordOpeningBalance: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['OpeningBalanceRequest'];
      };
    };
    responses: {
      /** @description Created */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OpeningBalanceResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       *     - `RETRY`: A concurrent request won a race for the same key; the retry answers with the winner.
       *     - `OPENING_BALANCE_AFTER_FIRST_TRANSACTION`: Live transactions are booked before the opening date (transactionCount, earliestBookingDate); move the date back or repeat with acknowledgeEarlierTransactions = true.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       *     - `OPENING_BALANCE_NOT_APPLICABLE`: This account takes no opening balance: a loan, a mortgage or a manually valued asset has a value source of its own.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  deleteOpeningBalance: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description No Content */
      204: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content?: never;
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       *     - `OPENING_BALANCE_NOT_RECORDED`: The account has no opening balance yet; record one with POST.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getAccountPositions: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountPositionsResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `POSITIONS_NOT_APPLICABLE`: This account does not hold securities positions.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  reassignAccountPortfolio: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['ReassignAccountPortfolioRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountSummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listReconciliations: {
    parameters: {
      query?: {
        /** @description Zero-based page number. */
        page?: number;
        /** @description Items per page; a larger value is capped to 200, and page.size in the response says the size applied. */
        size?: number;
        /** @description Repeatable <field>[,asc|desc]; any other field is a 400 INVALID_SORT. Default: createdAt,desc. The order always ends with a unique tie-breaker, so pages never overlap. */
        sort?: ('createdAt' | 'createdAt,asc' | 'createdAt,desc')[];
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PageResponseReconciliationResultResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       *     - `INVALID_SORT`: A paged list was asked to sort by a field it does not offer, or in a malformed way; detail names the field and the fields the list sorts by.
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getReconciliation: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        resultId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ReconciliationResultResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  acceptReconciliation: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        resultId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['ReconciliationDecisionRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ReconciliationResultResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `RECONCILIATION_STALE`: The reconciliation result no longer describes the newest comparison (status, differenceAmount); reload and decide again.
       *     - `RECONCILIATION_FINALIZED`: A newer snapshot has finalized this decision; correct the difference in the latest reconciliation.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  dismissReconciliation: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        resultId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['ReconciliationDecisionRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ReconciliationResultResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `RECONCILIATION_STALE`: The reconciliation result no longer describes the newest comparison (status, differenceAmount); reload and decide again.
       *     - `RECONCILIATION_FINALIZED`: A newer snapshot has finalized this decision; correct the difference in the latest reconciliation.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  reopenReconciliation: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        resultId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ReconciliationResultResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `RECONCILIATION_STALE`: The reconciliation result no longer describes the newest comparison (status, differenceAmount); reload and decide again.
       *     - `RECONCILIATION_FINALIZED`: A newer snapshot has finalized this decision; correct the difference in the latest reconciliation.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listLoanRepayments: {
    parameters: {
      query?: {
        /** @description Zero-based page number. */
        page?: number;
        /** @description Items per page; a larger value is capped to 200, and page.size in the response says the size applied. */
        size?: number;
        /** @description Repeatable <field>[,asc|desc]; any other field is a 400 INVALID_SORT. Default: bookingDate,desc. The order always ends with a unique tie-breaker, so pages never overlap. */
        sort?: ('bookingDate' | 'bookingDate,asc' | 'bookingDate,desc')[];
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PageResponseLoanRepaymentResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       *     - `INVALID_SORT`: A paged list was asked to sort by a field it does not offer, or in a malformed way; detail names the field and the fields the list sorts by.
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  recordLoanRepayment: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['CreateLoanRepaymentRequest'];
      };
    };
    responses: {
      /** @description Created */
      201: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['LoanRepaymentResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       *     - `ACCOUNT_ARCHIVED`: The account is archived; restore it first.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  restoreAccount: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountSummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  linkAccountSavingsPocket: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['LinkSavingsPocketRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountSummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `SAVINGS_POCKET_IN_USE`: The chosen account is already the savings pocket of another account.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `SAVINGS_POCKET_NOT_APPLICABLE`: The account can have no savings pocket: a card, a loan, an account without transactions, or one that is itself another account's pocket.
       *     - `SAVINGS_POCKET_UNSUITABLE`: The chosen account cannot be the savings pocket: the account itself, not active, without transactions, a card or loan, in another currency, or with a pocket of its own.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listAccountSnapshots: {
    parameters: {
      query?: {
        /** @description Zero-based page number. */
        page?: number;
        /** @description Items per page; a larger value is capped to 200, and page.size in the response says the size applied. */
        size?: number;
        /** @description Repeatable <field>[,asc|desc]; any other field is a 400 INVALID_SORT. Default: snapshotDate,desc. The order always ends with a unique tie-breaker, so pages never overlap. */
        sort?: ('snapshotDate' | 'snapshotDate,asc' | 'snapshotDate,desc')[];
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PageResponseAccountSnapshotResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       *     - `INVALID_SORT`: A paged list was asked to sort by a field it does not offer, or in a malformed way; detail names the field and the fields the list sorts by.
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  recordAccountSnapshot: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['RecordAccountSnapshotRequest'];
      };
    };
    responses: {
      /** @description Created */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountSnapshotResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       *     - `RETRY`: A concurrent request won a race for the same key; the retry answers with the winner.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getAccountSnapshot: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        snapshotId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountSnapshotResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  replaceAccountSnapshot: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        snapshotId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['ReplaceAccountSnapshotRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccountSnapshotResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listAccountTransactions: {
    parameters: {
      query?: {
        uncategorized?: boolean;
        /** @description Zero-based page number. */
        page?: number;
        /** @description Items per page; a larger value is capped to 200, and page.size in the response says the size applied. */
        size?: number;
        /** @description Repeatable <field>[,asc|desc]; any other field is a 400 INVALID_SORT. Default: bookingDate,desc. The order always ends with a unique tie-breaker, so pages never overlap. */
        sort?: (
          | 'bookingDate'
          | 'bookingDate,asc'
          | 'bookingDate,desc'
          | 'amount'
          | 'amount,asc'
          | 'amount,desc'
        )[];
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PageResponseTransactionResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       *     - `INVALID_SORT`: A paged list was asked to sort by a field it does not offer, or in a malformed way; detail names the field and the fields the list sorts by.
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  recordTransaction: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['CreateTransactionRequest'];
      };
    };
    responses: {
      /** @description Created */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TransactionResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       *     - `RETRY`: A concurrent request won a race for the same key; the retry answers with the winner.
       *     - `ACCOUNT_ARCHIVED`: The account is archived; restore it first.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listRemovedTransactions: {
    parameters: {
      query?: {
        /** @description Zero-based page number. */
        page?: number;
        /** @description Items per page; a larger value is capped to 200, and page.size in the response says the size applied. */
        size?: number;
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PageResponseTransactionResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       *     - `INVALID_SORT`: A paged list was asked to sort by a field it does not offer, or in a malformed way; detail names the field and the fields the list sorts by.
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  correctTransaction: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        transactionId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['CorrectTransactionRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TransactionCorrectionResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `ACCOUNT_ARCHIVED`: The account is archived; restore it first.
       *     - `RECONCILIATION_ADJUSTMENT_LOCKED`: The transaction is the adjusting entry of an accepted reconciliation; reopen the reconciliation result instead.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  removeTransaction: {
    parameters: {
      query?: {
        reason?: string;
      };
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        transactionId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TransactionRemovalResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `RECONCILIATION_ADJUSTMENT_LOCKED`: The transaction is the adjusting entry of an accepted reconciliation; reopen the reconciliation result instead.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  overrideTransactionCategory: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        transactionId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['SetTransactionCategoryRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TransactionResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `RECONCILIATION_ADJUSTMENT_LOCKED`: The transaction is the adjusting entry of an accepted reconciliation; reopen the reconciliation result instead.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  resetTransactionCategory: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        transactionId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TransactionResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `RECONCILIATION_ADJUSTMENT_LOCKED`: The transaction is the adjusting entry of an accepted reconciliation; reopen the reconciliation result instead.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  restoreTransaction: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        transactionId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TransactionRemovalResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `RECONCILIATION_ADJUSTMENT_LOCKED`: The transaction is the adjusting entry of an accepted reconciliation; reopen the reconciliation result instead.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  confirmUntrackedTransfer: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        transactionId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TransactionResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  undoUntrackedTransfer: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
        transactionId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TransactionResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listAccountValuations: {
    parameters: {
      query?: {
        /** @description Zero-based page number. */
        page?: number;
        /** @description Items per page; a larger value is capped to 200, and page.size in the response says the size applied. */
        size?: number;
        /** @description Repeatable <field>[,asc|desc]; any other field is a 400 INVALID_SORT. Default: valuationDate,desc. The order always ends with a unique tie-breaker, so pages never overlap. */
        sort?: ('valuationDate' | 'valuationDate,asc' | 'valuationDate,desc')[];
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PageResponseCustomAssetValuationResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       *     - `INVALID_SORT`: A paged list was asked to sort by a field it does not offer, or in a malformed way; detail names the field and the fields the list sorts by.
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  recordAccountValuation: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        accountId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['CreateCustomAssetValuationRequest'];
      };
    };
    responses: {
      /** @description Created */
      201: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CustomAssetValuationResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  runSettlementMatching: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        cardAccountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SettlementMatchRunResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getSettlementSource: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        cardAccountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SettlementSourceResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  setSettlementSource: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        cardAccountId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['SetSettlementSourceRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SettlementSourceResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `ACCOUNT_ARCHIVED`: The account is archived; restore it first.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getCurrentCardStatement: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        cardAccountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CardStatementResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getCardStatementConfig: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        cardAccountId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['StatementConfigResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  setCardStatementConfig: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        cardAccountId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['SetStatementConfigRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['StatementConfigResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getFxImportSettings: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['FxImportSettingsResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `FORBIDDEN`: Signed in, but not allowed to do this (e.g. an administration endpoint).
       */
      403: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  updateFxImportInterval: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['UpdateFxImportIntervalRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['FxImportSettingsResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `FORBIDDEN`: Signed in, but not allowed to do this (e.g. an administration endpoint).
       */
      403: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getReferenceData: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ReferenceDataResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `FORBIDDEN`: Signed in, but not allowed to do this (e.g. an administration endpoint).
       */
      403: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  createUser: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['CreateUserRequest'];
      };
    };
    responses: {
      /** @description Created */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UserSummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `FORBIDDEN`: Signed in, but not allowed to do this (e.g. an administration endpoint).
       */
      403: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getUser: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UserSummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `FORBIDDEN`: Signed in, but not allowed to do this (e.g. an administration endpoint).
       */
      403: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  updateUser: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['EditUserRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UserSummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `FORBIDDEN`: Signed in, but not allowed to do this (e.g. an administration endpoint).
       */
      403: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  disableUser: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UserSummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `FORBIDDEN`: Signed in, but not allowed to do this (e.g. an administration endpoint).
       */
      403: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  reactivateUser: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UserSummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `FORBIDDEN`: Signed in, but not allowed to do this (e.g. an administration endpoint).
       */
      403: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  login: {
    parameters: {
      query?: never;
      header?: {
        'User-Agent'?: string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['LoginRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['LoginResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `LOCKED`: The user account is temporarily locked after failed sign-ins.
       */
      423: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `RATE_LIMITED`: Too many requests; honour Retry-After.
       */
      429: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  verifyMfa: {
    parameters: {
      query?: never;
      header?: {
        'User-Agent'?: string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['MfaVerifyRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AuthTokensResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `LOCKED`: The user account is temporarily locked after failed sign-ins.
       */
      423: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `RATE_LIMITED`: Too many requests; honour Retry-After.
       */
      429: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  refreshToken: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['RefreshTokenRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AuthTokensResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `RATE_LIMITED`: Too many requests; honour Retry-After.
       */
      429: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getCashFlow: {
    parameters: {
      query: {
        month: string;
        currency?: string;
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CashFlowResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listCategories: {
    parameters: {
      query?: {
        includeInactive?: boolean;
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CategoryResponse'][];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  createCategory: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['CreateCategoryRequest'];
      };
    };
    responses: {
      /** @description Created */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CategoryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       *     - `RETRY`: A concurrent request won a race for the same key; the retry answers with the winner.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getCategory: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CategoryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  updateCategory: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['UpdateCategoryRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CategoryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `RETRY`: A concurrent request won a race for the same key; the retry answers with the winner.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  deleteCategory: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description No Content */
      204: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content?: never;
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  activateCategory: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CategoryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  deactivateCategory: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CategoryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listCategorizationRules: {
    parameters: {
      query?: {
        includeInactive?: boolean;
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CategorizationRuleResponse'][];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  createCategorizationRule: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['CreateCategorizationRuleRequest'];
      };
    };
    responses: {
      /** @description Created */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CategorizationRuleResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  deactivateCategorizationRule: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CategorizationRuleResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listImportTemplates: {
    parameters: {
      query?: {
        institutionCatalogueId?: string;
        includeInactive?: boolean;
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportTemplateResponse'][];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  createImportTemplate: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['ImportTemplateRequest'];
      };
    };
    responses: {
      /** @description Created */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportTemplateResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_TEMPLATE_INVALID`: The import template breaks a rule; field names the template field, detail the rule.
       *     - `IMPORT_TEMPLATE_UNSUPPORTED`: A valid template this release cannot import yet.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  detectImportTemplate: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: {
      content: {
        'multipart/form-data': {
          /** Format: binary */
          file: string;
        };
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportTemplateCandidateResponse'][];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_FILE_TOO_LARGE`: The uploaded file exceeds the 5 MB import limit.
       */
      413: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_TEMPLATE_INVALID`: The import template breaks a rule; field names the template field, detail the rule.
       *     - `IMPORT_TEMPLATE_UNSUPPORTED`: A valid template this release cannot import yet.
       *     - `IMPORT_TEMPLATE_MISMATCH`: The file's header lacks columns the template maps (missingColumns); no row is parsed.
       *     - `IMPORT_ENCODING_SUSPECT`: The header holds characters the template's encoding cannot decode; the file is probably in another encoding.
       *     - `IMPORT_FILE_EMPTY`: The file is empty, or holds nothing after the skipped preamble.
       *     - `IMPORT_FILE_MALFORMED`: The file is not valid CSV for the template's delimiter, or not a readable PDF, XML or spreadsheet (damaged, encrypted).
       *     - `IMPORT_FILE_TOO_MANY_ROWS`: The file has more data rows than one import may hold (maxRows).
       *     - `IMPORT_FILE_TOO_MANY_PAGES`: A PDF has more pages than one import may hold (maxPages).
       *     - `IMPORT_PDF_LIMIT_EXCEEDED`: A PDF holds more than one statement may (text, objects, images); exporting a shorter statement helps.
       *     - `IMPORT_PDF_NO_TEXT`: A scanned PDF without a text layer, but the template reads text; use an OCR template.
       *     - `IMPORT_SPREADSHEET_LIMIT_EXCEEDED`: A spreadsheet holds more sheets, rows, cells or text than one import may; exporting less helps.
       *     - `IMPORT_OCR_FAILED`: Local OCR could not read a scanned page (it failed or produced too much text).
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_PDF_BUSY`: The server is reading as many PDF statements as it may at once.
       *     - `IMPORT_SPREADSHEET_BUSY`: The server is reading as many spreadsheets as it may at once.
       *     - `IMPORT_OCR_UNAVAILABLE`: Local OCR is not installed, busy, or did not finish within the time limit.
       */
      503: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  testUnsavedImportTemplate: {
    parameters: {
      query?: {
        accountId?: string;
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: {
      content: {
        'multipart/form-data': {
          /** Format: binary */
          file: string;
          template: components['schemas']['ImportTemplateRequest'];
        };
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportTemplateTestResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_FILE_TOO_LARGE`: The uploaded file exceeds the 5 MB import limit.
       */
      413: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_TEMPLATE_INVALID`: The import template breaks a rule; field names the template field, detail the rule.
       *     - `IMPORT_TEMPLATE_UNSUPPORTED`: A valid template this release cannot import yet.
       *     - `IMPORT_TEMPLATE_MISMATCH`: The file's header lacks columns the template maps (missingColumns); no row is parsed.
       *     - `IMPORT_ENCODING_SUSPECT`: The header holds characters the template's encoding cannot decode; the file is probably in another encoding.
       *     - `IMPORT_FILE_EMPTY`: The file is empty, or holds nothing after the skipped preamble.
       *     - `IMPORT_FILE_NO_DATA_ROWS`: The file has a header but no data row.
       *     - `IMPORT_FILE_MALFORMED`: The file is not valid CSV for the template's delimiter, or not a readable PDF, XML or spreadsheet (damaged, encrypted).
       *     - `IMPORT_FILE_TOO_MANY_ROWS`: The file has more data rows than one import may hold (maxRows).
       *     - `IMPORT_FILE_TOO_MANY_PAGES`: A PDF has more pages than one import may hold (maxPages).
       *     - `IMPORT_PDF_LIMIT_EXCEEDED`: A PDF holds more than one statement may (text, objects, images); exporting a shorter statement helps.
       *     - `IMPORT_PDF_NO_TEXT`: A scanned PDF without a text layer, but the template reads text; use an OCR template.
       *     - `IMPORT_OCR_FAILED`: Local OCR could not read a scanned page (it failed or produced too much text).
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_PDF_BUSY`: The server is reading as many PDF statements as it may at once.
       *     - `IMPORT_OCR_UNAVAILABLE`: Local OCR is not installed, busy, or did not finish within the time limit.
       */
      503: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getImportTemplate: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportTemplateResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  updateImportTemplate: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['ImportTemplateRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportTemplateResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_TEMPLATE_READ_ONLY`: A shipped import template is shared by every workspace and never edited by one.
       */
      403: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_TEMPLATE_INVALID`: The import template breaks a rule; field names the template field, detail the rule.
       *     - `IMPORT_TEMPLATE_UNSUPPORTED`: A valid template this release cannot import yet.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  deleteImportTemplate: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description No Content */
      204: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content?: never;
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_TEMPLATE_READ_ONLY`: A shipped import template is shared by every workspace and never edited by one.
       */
      403: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_TEMPLATE_IN_USE`: An import batch used a version of this template, so it cannot be deleted; deactivate it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  activateImportTemplate: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportTemplateResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_TEMPLATE_READ_ONLY`: A shipped import template is shared by every workspace and never edited by one.
       */
      403: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  deactivateImportTemplate: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportTemplateResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_TEMPLATE_READ_ONLY`: A shipped import template is shared by every workspace and never edited by one.
       */
      403: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  testImportTemplate: {
    parameters: {
      query?: {
        accountId?: string;
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: {
      content: {
        'multipart/form-data': {
          /** Format: binary */
          file: string;
        };
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportTemplateTestResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_FILE_TOO_LARGE`: The uploaded file exceeds the 5 MB import limit.
       */
      413: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_TEMPLATE_INVALID`: The import template breaks a rule; field names the template field, detail the rule.
       *     - `IMPORT_TEMPLATE_UNSUPPORTED`: A valid template this release cannot import yet.
       *     - `IMPORT_TEMPLATE_MISMATCH`: The file's header lacks columns the template maps (missingColumns); no row is parsed.
       *     - `IMPORT_ENCODING_SUSPECT`: The header holds characters the template's encoding cannot decode; the file is probably in another encoding.
       *     - `IMPORT_FILE_EMPTY`: The file is empty, or holds nothing after the skipped preamble.
       *     - `IMPORT_FILE_NO_DATA_ROWS`: The file has a header but no data row.
       *     - `IMPORT_FILE_MALFORMED`: The file is not valid CSV for the template's delimiter, or not a readable PDF, XML or spreadsheet (damaged, encrypted).
       *     - `IMPORT_FILE_TOO_MANY_ROWS`: The file has more data rows than one import may hold (maxRows).
       *     - `IMPORT_FILE_TOO_MANY_PAGES`: A PDF has more pages than one import may hold (maxPages).
       *     - `IMPORT_PDF_LIMIT_EXCEEDED`: A PDF holds more than one statement may (text, objects, images); exporting a shorter statement helps.
       *     - `IMPORT_PDF_NO_TEXT`: A scanned PDF without a text layer, but the template reads text; use an OCR template.
       *     - `IMPORT_OCR_FAILED`: Local OCR could not read a scanned page (it failed or produced too much text).
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_PDF_BUSY`: The server is reading as many PDF statements as it may at once.
       *     - `IMPORT_OCR_UNAVAILABLE`: Local OCR is not installed, busy, or did not finish within the time limit.
       */
      503: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  proposeAccountFromFile: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        templateId: string;
      };
      cookie?: never;
    };
    requestBody?: {
      content: {
        'multipart/form-data': {
          /** Format: binary */
          file: string;
        };
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ImportAccountProposalResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_FILE_TOO_LARGE`: The uploaded file exceeds the 5 MB import limit.
       */
      413: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_TEMPLATE_INVALID`: The import template breaks a rule; field names the template field, detail the rule.
       *     - `IMPORT_TEMPLATE_UNSUPPORTED`: A valid template this release cannot import yet.
       *     - `IMPORT_TEMPLATE_MISMATCH`: The file's header lacks columns the template maps (missingColumns); no row is parsed.
       *     - `IMPORT_ENCODING_SUSPECT`: The header holds characters the template's encoding cannot decode; the file is probably in another encoding.
       *     - `IMPORT_FILE_EMPTY`: The file is empty, or holds nothing after the skipped preamble.
       *     - `IMPORT_FILE_NO_DATA_ROWS`: The file has a header but no data row.
       *     - `IMPORT_FILE_MALFORMED`: The file is not valid CSV for the template's delimiter, or not a readable PDF, XML or spreadsheet (damaged, encrypted).
       *     - `IMPORT_FILE_TOO_MANY_ROWS`: The file has more data rows than one import may hold (maxRows).
       *     - `IMPORT_FILE_TOO_MANY_PAGES`: A PDF has more pages than one import may hold (maxPages).
       *     - `IMPORT_PDF_LIMIT_EXCEEDED`: A PDF holds more than one statement may (text, objects, images); exporting a shorter statement helps.
       *     - `IMPORT_PDF_NO_TEXT`: A scanned PDF without a text layer, but the template reads text; use an OCR template.
       *     - `IMPORT_OCR_FAILED`: Local OCR could not read a scanned page (it failed or produced too much text).
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `IMPORT_PDF_BUSY`: The server is reading as many PDF statements as it may at once.
       *     - `IMPORT_OCR_UNAVAILABLE`: Local OCR is not installed, busy, or did not finish within the time limit.
       */
      503: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  searchInstitutionCatalogue: {
    parameters: {
      query?: {
        query?: string;
        /** @description US-04-10: ISO 3166-1 alpha-2 country codes, repeated or comma-separated; blank values are ignored. Any other value (such as a three-letter code) is a 400 VALIDATION_FAILED. */
        country?: string[];
        /** @description Zero-based page number. */
        page?: number;
        /** @description Items per page; a larger value is capped to 200, and page.size in the response says the size applied. */
        size?: number;
        /** @description Repeatable <field>[,asc|desc]; any other field is a 400 INVALID_SORT. Default: name,asc. The order always ends with a unique tie-breaker, so pages never overlap. */
        sort?: ('name' | 'name,asc' | 'name,desc')[];
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PageResponseInstitutionCatalogueEntrySummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       *     - `INVALID_SORT`: A paged list was asked to sort by a field it does not offer, or in a malformed way; detail names the field and the fields the list sorts by.
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getNetWorth: {
    parameters: {
      query?: {
        currency?: string;
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['NetWorthResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listPortfolios: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PortfolioListItemResponse'][];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  createPortfolio: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['CreatePortfolioRequest'];
      };
    };
    responses: {
      /** @description Created */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PortfolioResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getPortfolio: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        portfolioId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PortfolioResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  updatePortfolio: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        portfolioId: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['UpdatePortfolioRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PortfolioResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getPortfolioSummary: {
    parameters: {
      query?: {
        currency?: string;
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        portfolioId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PortfolioSummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getPortfolioView: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        portfolioId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PortfolioViewResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  findSecurity: {
    parameters: {
      query: {
        isin: string;
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SecurityResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `RATE_LIMITED`: Too many requests; honour Retry-After.
       */
      429: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  findOrCreateSecurity: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['CreateSecurityRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SecurityResponse'];
        };
      };
      /** @description Created */
      201: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SecurityResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `RATE_LIMITED`: Too many requests; honour Retry-After.
       */
      429: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getSecurity: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SecurityResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listSessions: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SessionSummaryResponse'][];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  revokeSession: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SessionSummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `RATE_LIMITED`: Too many requests; honour Retry-After.
       */
      429: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listSettlementMatches: {
    parameters: {
      query?: {
        /** @description The status to list. */
        status?: 'PROPOSED' | 'CONFIRMED' | 'REJECTED';
        /** @description Lists one kind of match only; both kinds when absent. */
        kind?: 'CARD_SETTLEMENT' | 'TRANSFER';
        /** @description Only matches belonging to this card or credited account. */
        cardAccountId?: string;
        /** @description Zero-based page number. */
        page?: number;
        /** @description Items per page; a larger value is capped to 200, and page.size in the response says the size applied. */
        size?: number;
        /** @description Repeatable <field>[,asc|desc]; any other field is a 400 INVALID_SORT. Default: createdAt,desc. The order always ends with a unique tie-breaker, so pages never overlap. */
        sort?: ('createdAt' | 'createdAt,asc' | 'createdAt,desc')[];
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PageResponseSettlementMatchResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       *     - `INVALID_SORT`: A paged list was asked to sort by a field it does not offer, or in a malformed way; detail names the field and the fields the list sorts by.
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  confirmSettlementMatch: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        matchId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SettlementMatchResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  rejectSettlementMatch: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        matchId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SettlementMatchResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  setupAdministrator: {
    parameters: {
      query?: never;
      header?: {
        'User-Agent'?: string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['SetupAdministratorRequest'];
      };
    };
    responses: {
      /** @description Created */
      201: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AuthTokensResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `RATE_LIMITED`: Too many requests; honour Retry-After.
       */
      429: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listWorkspaceTransactions: {
    parameters: {
      query?: {
        accountId?: string[];
        portfolioId?: string[];
        categoryId?: string[];
        bookingDateFrom?: string;
        bookingDateTo?: string;
        uncategorized?: boolean;
        q?: string;
        amountMin?: DecimalString;
        amountMax?: DecimalString;
        currency?: string;
        /** @description Zero-based page number. */
        page?: number;
        /** @description Items per page; a larger value is capped to 200, and page.size in the response says the size applied. */
        size?: number;
        /** @description Repeatable <field>[,asc|desc]; any other field is a 400 INVALID_SORT. Default: bookingDate,desc. The order always ends with a unique tie-breaker, so pages never overlap. */
        sort?: (
          | 'bookingDate'
          | 'bookingDate,asc'
          | 'bookingDate,desc'
          | 'amount'
          | 'amount,asc'
          | 'amount,desc'
        )[];
      };
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PageResponseTransactionResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       *     - `INVALID_SORT`: A paged list was asked to sort by a field it does not offer, or in a malformed way; detail names the field and the fields the list sorts by.
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getCurrentUser: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CurrentUserResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  confirmMfa: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['MfaConfirmRequest'];
      };
    };
    responses: {
      /** @description No Content */
      204: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content?: never;
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `LOCKED`: The user account is temporarily locked after failed sign-ins.
       */
      423: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `RATE_LIMITED`: Too many requests; honour Retry-After.
       */
      429: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  disableMfa: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['MfaDisableRequest'];
      };
    };
    responses: {
      /** @description No Content */
      204: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content?: never;
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `LOCKED`: The user account is temporarily locked after failed sign-ins.
       */
      423: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  enrollMfa: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['MfaEnrollmentRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['MfaEnrollmentResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `LOCKED`: The user account is temporarily locked after failed sign-ins.
       */
      423: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          /** @description Seconds to wait before retrying, when the server can tell. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getWorkspace: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkspaceResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  listWorkspaceMembers: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkspaceMemberListItemResponse'][];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNPROCESSABLE`: Well-formed, but breaks a business rule; detail names the rule.
       */
      422: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  getWorkspaceMember: {
    parameters: {
      query?: never;
      header?: {
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        memberId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkspaceMemberSummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  deactivateWorkspaceMember: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path: {
        memberId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkspaceMemberSummaryResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `CONFLICT`: The request conflicts with the current state; detail says how.
       */
      409: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  updateWorkspaceCurrency: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['UpdateWorkspaceCurrencyRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkspaceResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
  updateWorkspaceName: {
    parameters: {
      query?: never;
      header: {
        /** @description Strong ETag of the resource version last read, for example "7". Missing values return 428 VERSION_REQUIRED; stale values return 412 VERSION_CONFLICT. */
        'If-Match': string;
        /** @description Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the server generates a UUID. */
        'X-Correlation-Id'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['UpdateWorkspaceNameRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkspaceResponse'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VALIDATION_FAILED`: The request body or parameters are malformed or fail validation; errors lists each rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).
       */
      400: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `UNAUTHENTICATED`: No valid access token, or a sign-in was refused.
       */
      401: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `NOT_FOUND`: Not found, or not visible to the caller; the two are never told apart.
       */
      404: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_CONFLICT`: The record changed since it was read: 412 with If-Match, 409 for a lost race without it.
       */
      412: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `VERSION_REQUIRED`: A state-changing request omitted the If-Match version it must protect.
       */
      428: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /**
       * @description Problem codes:
       *     - `INTERNAL`: Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code of its own.
       */
      500: {
        headers: {
          /** @description Correlation id assigned to this request. */
          'X-Correlation-Id'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      default: components['responses']['ApiProblemResponse'];
    };
  };
}

package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * US-07-34 (#365): a statement uploaded without an account, as the routing hands it on to the
 * account upload once it knows the account: the file's name and media type, the template it was
 * read with ({@code null} for a CAMT statement), who uploads it and the upload's {@code
 * Idempotency-Key} claim, if any. The file's bytes travel beside it.
 */
public record ImportUpload(
    String fileName,
    String mediaType,
    @Nullable UUID templateId,
    AuthenticatedUserPrincipal actor,
    @Nullable IdempotencyClaim claim) {}

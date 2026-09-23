package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CreateSecurityRequest;
import com.trackmywealth.backend.dto.SecurityCompleteness;
import com.trackmywealth.backend.dto.SecurityCreation;
import com.trackmywealth.backend.dto.SecurityResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Security;
import com.trackmywealth.backend.entity.SecurityAssetClassWeight;
import com.trackmywealth.backend.entity.SecurityFieldProvenance;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.SecurityAssetClassWeightRepository;
import com.trackmywealth.backend.repository.SecurityFieldProvenanceRepository;
import com.trackmywealth.backend.repository.SecurityRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-12-01/FR-SMD-001/004/007: the global security master is created lazily, on first reference,
 * and shared by every workspace thereafter - ten workspaces holding the same ETF create one row.
 *
 * <p>There is no external reference-data provider yet (OPEN-005), so the only source is a
 * hand-entered record; manual mode is the supported baseline (NFR-LIC-004/008), and everything
 * downstream treats such a record exactly like any other. {@link #lookup} never writes
 * (FR-SMD-007); only an explicit {@link #findOrCreate} does.
 *
 * <p>The table is shared, unscoped reference data (NFR-LIC-007), so: an existing record always wins
 * and is returned unchanged whatever a later caller supplies; nothing on the row or in its
 * provenance identifies the workspace or user that caused it to exist; and creating one needs EDIT
 * on at least one active account of the caller's workspace - a member who cannot record a
 * transaction has no reason to add to the shared master.
 */
@Service
public class SecurityService {

  private static final String MANUAL = "MANUAL";
  private static final String SYNTHETIC_PREFIX = "MANUAL-";
  private static final String EQUITY = "EQUITY";
  private static final String ACTIVE = "ACTIVE";
  private static final String DERIVED = "DERIVED";
  private static final int ACCESS_CHECK_BATCH = 20;

  private final SecurityRepository securityRepository;
  private final SecurityAssetClassWeightRepository weightRepository;
  private final SecurityFieldProvenanceRepository provenanceRepository;
  private final AccountRepository accountRepository;
  private final AccessControlService accessControlService;

  public SecurityService(
      SecurityRepository securityRepository,
      SecurityAssetClassWeightRepository weightRepository,
      SecurityFieldProvenanceRepository provenanceRepository,
      AccountRepository accountRepository,
      AccessControlService accessControlService) {
    this.securityRepository = securityRepository;
    this.weightRepository = weightRepository;
    this.provenanceRepository = provenanceRepository;
    this.accountRepository = accountRepository;
    this.accessControlService = accessControlService;
  }

  /** Read-only lookup by ISIN; writes nothing (FR-SMD-007). Any workspace member may look up. */
  @Transactional(readOnly = true)
  public SecurityResponse lookup(String isin, AuthenticatedUserPrincipal actor) {
    accessControlService.requireActingMember(actor);
    String normalized = isin == null ? "" : isin.trim().toUpperCase(Locale.ROOT);
    return securityRepository
        .findByIsin(normalized)
        .map(this::toResponse)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found."));
  }

  /**
   * Read-only lookup by id - the only way to find a security that has no ISIN again. The id is an
   * unguessable UUID and there is deliberately no search or listing of the shared master, so a
   * hand-entered private holding is not discoverable by another workspace (NFR-LIC-007).
   */
  @Transactional(readOnly = true)
  public SecurityResponse get(UUID id, AuthenticatedUserPrincipal actor) {
    accessControlService.requireActingMember(actor);
    return securityRepository
        .findById(id)
        .map(this::toResponse)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found."));
  }

  /**
   * Returns the record for the request's ISIN, creating it only if none exists. Without an ISIN a
   * new record is created under a generated synthetic key - or, when the request carries an {@code
   * idempotencyKey}, under a key derived from it and the caller's workspace, so a retry finds the
   * record instead of adding a duplicate. Safe under concurrent creation: exactly one row results
   * (see {@code SecurityRepository#insertIfAbsent}).
   */
  @Transactional
  public SecurityCreation findOrCreate(
      CreateSecurityRequest request, AuthenticatedUserPrincipal actor) {
    requireMayCreate(actor);
    if (request.isin() != null && request.idempotencyKey() != null) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "idempotencyKey is only for a security without an ISIN; the ISIN already is its identity.");
    }

    UUID id = UUID.randomUUID();
    String syntheticKey = request.isin() == null ? syntheticKeyFor(request, actor, id) : null;
    int inserted =
        securityRepository.insertIfAbsent(
            id,
            request.isin(),
            syntheticKey,
            request.legalName() == null ? request.displayName() : request.legalName(),
            request.displayName(),
            request.instrumentType(),
            request.securityCountry(),
            request.issuerCountry(),
            request.denominationCurrency());
    if (inserted == 0) {
      // Lost the race to (or simply repeats) an existing record: it wins, unchanged. Only an ISIN
      // or an idempotency-derived key can conflict, so exactly one of the two lookups applies.
      Security existing =
          (request.isin() != null
                  ? securityRepository.findByIsin(request.isin())
                  : securityRepository.findBySyntheticKey(syntheticKey))
              .orElseThrow();
      SecurityResponse response = toResponse(existing);
      return new SecurityCreation(response, false, ignoredFields(request, response));
    }

    recordAssetClass(id, request.assetClass());
    recordProvenance(id, request);
    Security created = securityRepository.findById(id).orElseThrow();
    return new SecurityCreation(toResponse(created), true, List.of());
  }

  // A one-way hash of (workspace, key): stable for a retry, different for every workspace, and it
  // reveals neither the key nor the workspace (NFR-LIC-007). Without a key, a fresh random key.
  private static String syntheticKeyFor(
      CreateSecurityRequest request, AuthenticatedUserPrincipal actor, UUID freshId) {
    if (request.idempotencyKey() == null) {
      return SYNTHETIC_PREFIX + freshId;
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (actor.workspaceId() + ":" + request.idempotencyKey())
                      .getBytes(StandardCharsets.UTF_8));
      return SYNTHETIC_PREFIX + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", e);
    }
  }

  // The fields the caller supplied that differ from what is stored. Optional fields the caller
  // left out are not a difference.
  private static List<String> ignoredFields(
      CreateSecurityRequest request, SecurityResponse existing) {
    List<String> ignored = new ArrayList<>();
    addIfDiffers(ignored, "displayName", request.displayName(), existing.displayName());
    addIfDiffers(
        ignored,
        "denominationCurrency",
        request.denominationCurrency(),
        existing.denominationCurrency());
    addIfDiffers(ignored, "instrumentType", request.instrumentType(), existing.instrumentType());
    addIfDiffers(ignored, "assetClass", request.assetClass(), existing.assetClass());
    addIfDiffers(ignored, "securityCountry", request.securityCountry(), existing.securityCountry());
    addIfDiffers(ignored, "issuerCountry", request.issuerCountry(), existing.issuerCountry());
    addIfDiffers(ignored, "legalName", request.legalName(), existing.legalName());
    return ignored;
  }

  private static void addIfDiffers(
      List<String> ignored, String field, String supplied, String stored) {
    if (supplied != null && !supplied.equals(stored)) {
      ignored.add(field);
    }
  }

  // Stops at the first batch containing an account the member may edit, so a member with many
  // accounts does not pay for evaluating every one of them. Batches (not one call per account)
  // keep AccessControlService's bulk sole-member optimisation.
  private void requireMayCreate(AuthenticatedUserPrincipal actor) {
    UUID memberId = accessControlService.requireActingMember(actor);
    List<Account> accounts =
        accountRepository.findByWorkspaceIdAndStatusOrderByCreatedAtAsc(
            actor.workspaceId(), ACTIVE);
    for (int from = 0; from < accounts.size(); from += ACCESS_CHECK_BATCH) {
      List<Account> batch =
          accounts.subList(from, Math.min(from + ACCESS_CHECK_BATCH, accounts.size()));
      if (!accessControlService
          .accountsWithAccess(memberId, batch, AccessLevelValues.EDIT)
          .isEmpty()) {
        return;
      }
    }
    // Same 404 as every other denial (FR-TEN-006): no hint of what exists.
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found.");
  }

  private void recordAssetClass(UUID securityId, String assetClass) {
    SecurityAssetClassWeight weight = new SecurityAssetClassWeight();
    weight.setSecurityId(securityId);
    weight.setAssetClass(assetClass);
    weight.setWeight(BigDecimal.ONE);
    weight.setEstimated(true); // FR-CLS-005: a declared allocation, not look-through data
    weight.setSource(MANUAL);
    weightRepository.save(weight);
  }

  private void recordProvenance(UUID securityId, CreateSecurityRequest request) {
    List<String> fields =
        new ArrayList<>(
            List.of("displayName", "denominationCurrency", "instrumentType", "assetClass"));
    if (request.isin() != null) {
      fields.add("isin");
    }
    if (request.securityCountry() != null) {
      fields.add("securityCountry");
    }
    if (request.issuerCountry() != null) {
      fields.add("issuerCountry");
    }
    List<SecurityFieldProvenance> rows = new ArrayList<>();
    for (String field : fields) {
      rows.add(provenance(securityId, field, null));
    }
    // A legal name the caller did not give is the display name standing in for it - recorded as
    // such, so nothing downstream mistakes it for an authoritative legal name.
    rows.add(provenance(securityId, "legalName", request.legalName() == null ? DERIVED : null));
    provenanceRepository.saveAll(rows);
  }

  private static SecurityFieldProvenance provenance(
      UUID securityId, String field, String confidence) {
    SecurityFieldProvenance row = new SecurityFieldProvenance();
    row.setSecurityId(securityId);
    row.setFieldName(field);
    row.setSource(MANUAL);
    row.setConfidence(confidence);
    return row;
  }

  private SecurityResponse toResponse(Security security) {
    Optional<SecurityAssetClassWeight> dominant =
        weightRepository.findBySecurityIdOrderByWeightDesc(security.getId()).stream().findFirst();
    return new SecurityResponse(
        security.getId(),
        security.getIsin(),
        security.getSyntheticKey(),
        security.getDisplayName(),
        security.getLegalName(),
        security.getDenominationCurrency(),
        security.getInstrumentType(),
        dominant.map(SecurityAssetClassWeight::getAssetClass).orElse(null),
        security.getSecurityCountry(),
        security.getIssuerCountry(),
        security.getState(),
        completeness(security, dominant.isPresent()));
  }

  // FR-SMD-011: the analysis-relevant fields. GICS applies to equity-nature instruments only
  // (FR-GICS-003), so its absence is a gap only for those.
  private static SecurityCompleteness completeness(Security security, boolean hasAssetClass) {
    List<String> missing = new ArrayList<>();
    if (security.getInstrumentType() == null) {
      missing.add("instrumentType");
    }
    if (!hasAssetClass) {
      missing.add("assetClass");
    }
    if (security.getSecurityCountry() == null) {
      missing.add("securityCountry");
    }
    if (security.getIssuerCountry() == null) {
      missing.add("issuerCountry");
    }
    if (EQUITY.equals(security.getInstrumentType()) && security.getGicsSubIndustryCode() == null) {
      missing.add("gicsSubIndustry");
    }
    return new SecurityCompleteness(missing.isEmpty(), List.copyOf(missing));
  }
}

package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.Security;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SecurityRepository extends JpaRepository<Security, UUID> {

  Optional<Security> findByIsin(String isin);

  Optional<Security> findBySyntheticKey(String syntheticKey);

  /**
   * Inserts the row unless one with the same ISIN (or, for an idempotent no-ISIN request, the same
   * synthetic key) already exists, returning the number of rows written (1 or 0). {@code ON
   * CONFLICT DO NOTHING} rather than catching a unique violation: in PostgreSQL a violation aborts
   * the whole transaction, and two workspaces buying the same new ISIN at the same moment is
   * exactly the case this exists for - the loser blocks on the winner's uncommitted row, then
   * simply gets 0 and reads the winner's record.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO security (id, isin, synthetic_key, legal_name, display_name,"
              + " instrument_type, security_country, issuer_country, denomination_currency)"
              + " VALUES (:id, :isin, :syntheticKey, :legalName, :displayName, :instrumentType,"
              + " :securityCountry, :issuerCountry, :currency)"
              + " ON CONFLICT DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("id") UUID id,
      @Param("isin") String isin,
      @Param("syntheticKey") String syntheticKey,
      @Param("legalName") String legalName,
      @Param("displayName") String displayName,
      @Param("instrumentType") String instrumentType,
      @Param("securityCountry") String securityCountry,
      @Param("issuerCountry") String issuerCountry,
      @Param("currency") String currency);
}

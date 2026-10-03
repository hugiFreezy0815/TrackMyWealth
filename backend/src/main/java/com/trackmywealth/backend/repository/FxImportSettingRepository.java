package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.FxImportSetting;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface FxImportSettingRepository extends JpaRepository<FxImportSetting, UUID> {

  // V61 creates the one row and its singleton constraint allows no second one; empty only if the
  // row was deleted by hand.
  @Query("SELECT s FROM FxImportSetting s")
  Optional<FxImportSetting> findTheSetting();
}

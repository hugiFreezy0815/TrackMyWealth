package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.AppUser;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

  // citext equality is already case-insensitive at the database level, so a plain `= ?` here
  // gives the right semantics without needing an IgnoreCase-suffixed method name.
  boolean existsByEmail(String email);
}

package com.trackmywealth.backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * Generates and hashes opaque refresh tokens - shared by issuance ({@link TokenIssuanceService})
 * and rotation ({@link TokenRotationService}), which both need identical hashing for a
 * lookup-by-hash to ever match. A fast cryptographic hash is the right tool here - unlike a
 * user-chosen password, brute-forcing the hash preimage of a 256-random-bit token is infeasible
 * regardless of hash speed. Argon2id (see {@code SecurityConfig.passwordEncoder}) is reserved for
 * human-chosen secrets.
 */
@Service
public class TokenHashingService {

  private final SecureRandom secureRandom = new SecureRandom();

  public String generateOpaqueToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hashed);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is a JVM-mandatory algorithm (JLS/JCA baseline) - this cannot happen on any
      // conforming JVM.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}

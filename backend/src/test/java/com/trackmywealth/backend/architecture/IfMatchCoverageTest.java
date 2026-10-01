package com.trackmywealth.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR 0004 / #207 (FR-CNC-001): every mutating endpoint takes {@code If-Match}, so no new
 * read-modify-write endpoint can silently be last-write-wins again.
 *
 * <p>The few that legitimately don't are listed here by name with the reason, mirroring ADR 0004's
 * "Not read-modify-write" list; any other {@code PUT}/{@code PATCH}/{@code DELETE}/{@code POST}
 * handler without the header fails the build. Adding an entry means updating the ADR too.
 */
class IfMatchCoverageTest {

  private static final String CONTROLLER_PACKAGE = "com.trackmywealth.backend.controller";

  static final Map<String, String> REVIEWED_WITHOUT_IF_MATCH =
      Map.ofEntries(
          // Creates: POST on a collection, no prior version to protect.
          Map.entry("AccountController#createAccount", "create"),
          Map.entry("AccountSnapshotController#recordSnapshot", "create"),
          Map.entry("AdminUserController#createUser", "create"),
          Map.entry("CategorizationRuleController#create", "create"),
          Map.entry("CategoryController#create", "create"),
          Map.entry("CustomAssetValuationController#recordValuation", "create (append-only)"),
          Map.entry("InstitutionController#createInstitution", "create"),
          Map.entry("SharingGrantController#grant", "create"),
          Map.entry("TransactionController#recordTransaction", "create (append-only ledger)"),
          Map.entry("SecurityController#findOrCreate", "idempotent find-or-create of shared data"),
          // Batch: re-runs matching for a card; idempotent, no client-held state.
          Map.entry("SettlementMatchController#run", "idempotent batch"),
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
          Map.entry("SessionController#revokeSession", "idempotent terminal transition"));

  @Test
  void everyMutatingEndpointTakesIfMatchOrIsAReviewedException() {
    assertThat(mutatingEndpointsWithoutIfMatch())
        .as(
            "mutating endpoints without If-Match that are not listed in REVIEWED_WITHOUT_IF_MATCH"
                + " (and ADR 0004)")
        .isSubsetOf(REVIEWED_WITHOUT_IF_MATCH.keySet());
  }

  @Test
  void everyReviewedExceptionStillExistsAndStillLacksIfMatch() {
    assertThat(mutatingEndpointsWithoutIfMatch())
        .as("stale REVIEWED_WITHOUT_IF_MATCH entries - remove them (and from ADR 0004)")
        .containsAll(REVIEWED_WITHOUT_IF_MATCH.keySet());
  }

  private static Set<String> mutatingEndpointsWithoutIfMatch() {
    Set<String> missing = new TreeSet<>();
    for (JavaClass controller :
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(CONTROLLER_PACKAGE)) {
      Class<?> type = controller.reflect();
      if (!type.isAnnotationPresent(RestController.class)) {
        continue;
      }
      for (Method method : type.getDeclaredMethods()) {
        if (isMutating(method) && !takesIfMatch(method)) {
          missing.add(type.getSimpleName() + "#" + method.getName());
        }
      }
    }
    assertThat(missing).as("the scan found the controllers at all").isNotEmpty();
    return missing;
  }

  private static boolean isMutating(Method method) {
    return method.isAnnotationPresent(PutMapping.class)
        || method.isAnnotationPresent(PatchMapping.class)
        || method.isAnnotationPresent(DeleteMapping.class)
        || method.isAnnotationPresent(PostMapping.class);
  }

  private static boolean takesIfMatch(Method method) {
    for (Parameter parameter : method.getParameters()) {
      RequestHeader header = parameter.getAnnotation(RequestHeader.class);
      if (header != null
          && (IfMatchVersionParser.HEADER.equalsIgnoreCase(header.value())
              || IfMatchVersionParser.HEADER.equalsIgnoreCase(header.name()))) {
        return true;
      }
    }
    return false;
  }
}

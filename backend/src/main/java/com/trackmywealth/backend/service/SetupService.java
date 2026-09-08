package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.FinancialInstitutionRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-01-03: the one-time bootstrap that creates the initial {@code SYSTEM_ADMINISTRATOR} and their
 * workspace. Implements the exact transaction sequence documented in {@code
 * V19__seed_reference_data.sql}'s trailing comment:
 *
 * <ol>
 *   <li>Pre-generate the workspace's UUID in application code (not left to the database) - the
 *       row-level-security policies in V20 need {@code app.current_workspace_id} set to this value
 *       before the workspace row (and everything the V19 trigger creates off the back of it) is
 *       inserted.
 *   <li>{@code SELECT set_config('app.current_workspace_id', ?, true)} - transaction-local, so it
 *       cannot leak into any other request sharing a pooled connection.
 *   <li>Insert the workspace. This fires V19's {@code workspace_create_personal_assets_container}
 *       trigger, which inserts the default "Personal Assets" {@link FinancialInstitution} - its own
 *       {@code INSERT} satisfies that table's RLS policy only because step 2 already ran.
 *   <li>Insert the workspace member and the administrator's {@link AppUser}, and correct the
 *       Personal Assets container's placeholder {@code CHF} currency to the one requested.
 * </ol>
 *
 * The general-purpose "set the workspace context for any request" mechanism this pattern
 * foreshadows is US-28-01's job (see {@code
 * docs/architecture/adr/0002-workspace-context-propagation.md}) - this class only needs it for its
 * own one-off bootstrap transaction, so it calls {@code set_config} directly rather than depending
 * on infrastructure that doesn't exist yet.
 */
@Service
public class SetupService {

  // An arbitrary, reserved PostgreSQL advisory lock key scoped to this one bootstrap flow.
  // pg_advisory_xact_lock is transaction-scoped and releases automatically at commit/rollback, so
  // no matching unlock call is needed. Two concurrent setup attempts serialize on this lock; the
  // second one only proceeds - and then correctly observes count() > 0 - after the first commits.
  private static final long SETUP_BOOTSTRAP_LOCK_KEY = 727100010103L;

  private final EntityManager entityManager;
  private final WorkspaceRepository workspaceRepository;
  private final WorkspaceMemberRepository workspaceMemberRepository;
  private final FinancialInstitutionRepository financialInstitutionRepository;
  private final AppUserRepository appUserRepository;
  private final PasswordEncoder passwordEncoder;
  private final TokenIssuanceService tokenIssuanceService;

  public SetupService(
      EntityManager entityManager,
      WorkspaceRepository workspaceRepository,
      WorkspaceMemberRepository workspaceMemberRepository,
      FinancialInstitutionRepository financialInstitutionRepository,
      AppUserRepository appUserRepository,
      PasswordEncoder passwordEncoder,
      TokenIssuanceService tokenIssuanceService) {
    this.entityManager = entityManager;
    this.workspaceRepository = workspaceRepository;
    this.workspaceMemberRepository = workspaceMemberRepository;
    this.financialInstitutionRepository = financialInstitutionRepository;
    this.appUserRepository = appUserRepository;
    this.passwordEncoder = passwordEncoder;
    this.tokenIssuanceService = tokenIssuanceService;
  }

  @Transactional
  public AuthTokensResponse bootstrapInitialAdministrator(
      SetupAdministratorRequest request, String deviceLabel, String rawIpAddress) {
    acquireSetupBootstrapLock();
    if (appUserRepository.count() > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Setup has already been completed for this deployment.");
    }

    UUID workspaceId = UUID.randomUUID();
    setWorkspaceContext(workspaceId);

    Workspace workspace = new Workspace();
    workspace.setId(workspaceId);
    workspace.setName(request.workspaceName());
    // Must flush now, not defer to end-of-transaction: V19's AFTER INSERT trigger creates the
    // Personal Assets container as a side effect invisible to Hibernate's change tracking, and
    // the very next line reads it back through a query Hibernate has no reason to auto-flush for
    // (its pending dirty state is on Workspace, not FinancialInstitution).
    workspaceRepository.saveAndFlush(workspace);

    FinancialInstitution personalAssets =
        financialInstitutionRepository
            .findByWorkspaceIdAndPersonalAssetsDefaultTrue(workspaceId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "V19's workspace_create_personal_assets_container trigger did not create"
                            + " a Personal Assets container for workspace "
                            + workspaceId));
    personalAssets.setContainerCurrency(request.currencyCode());
    financialInstitutionRepository.save(personalAssets);

    WorkspaceMember administratorMember = new WorkspaceMember();
    administratorMember.setWorkspace(workspace);
    // No separate "your name" field exists in this story's request - the workspace display name
    // and credentials are all it collects. Falling back to the email's local part is a reasonable
    // placeholder; revisit if a later story adds an explicit display-name field to setup.
    administratorMember.setDisplayName(request.email().split("@", 2)[0]);
    administratorMember.setDependent(false);
    administratorMember = workspaceMemberRepository.save(administratorMember);

    AppUser administrator = new AppUser();
    administrator.setEmail(request.email());
    administrator.setPasswordHash(passwordEncoder.encode(request.password()));
    administrator.setRole("SYSTEM_ADMINISTRATOR");
    administrator.setReportingCurrency(request.currencyCode());
    administrator.setWorkspaceMember(administratorMember);
    administrator = appUserRepository.save(administrator);

    return tokenIssuanceService.issueTokens(administrator, deviceLabel, rawIpAddress);
  }

  private void setWorkspaceContext(UUID workspaceId) {
    entityManager
        .createNativeQuery("SELECT set_config('app.current_workspace_id', :workspaceId, true)")
        .setParameter("workspaceId", workspaceId.toString())
        .getSingleResult();
  }

  private void acquireSetupBootstrapLock() {
    entityManager
        .createNativeQuery("SELECT pg_advisory_xact_lock(:lockKey)")
        .setParameter("lockKey", SETUP_BOOTSTRAP_LOCK_KEY)
        .getSingleResult();
  }
}

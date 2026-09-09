package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AppUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression test for #62: {@link AdminUserService#disableUser} and {@link
 * AdminUserService#reactivateUser} both mutate a target's {@code app_user} row and then - in
 * opposite order, forced by a real FK constraint on {@code reactivateUser}'s side - mutate {@code
 * refresh_token}/{@code user_session} for the same user. Confirmed against a real Postgres
 * instance: enough concurrent admin actions racing the same target can deadlock, not (only) on
 * {@code refresh_token}/{@code user_session} as first suspected, but on the shared {@code app_user}
 * row itself - several concurrent optimistic-locked UPDATEs to one row can produce a genuine
 * wait-for cycle via Postgres's tuple-lock queueing, not just a plain serialize-and-wait.
 *
 * <p>Unlike {@link TokenRotationSessionRevocationConcurrencyTest} (where a single racing pair is
 * usually enough, since {@code rotate()}/{@code revokeSession()} unconditionally target the exact
 * same two rows every time), reproducing *this* deadlock needed many concurrent writers to the same
 * {@code app_user} row, not just two - a simple one-disable-vs-one-reactivate pair reliably just
 * serializes. So every pair here deliberately races the same single target, repeated across several
 * rounds with a fresh target each round for statistical coverage.
 *
 * <p>The fix ({@code AppUserRepository.findByIdForUpdate}, a {@code SELECT ... FOR UPDATE} both
 * methods now issue as their first statement) closes this by fully serializing any two transactions
 * on the same target - the second can't even read the row until the first commits - so this test's
 * job is to fail the moment a future change removes that upfront lock, not to prove today's
 * ordering of the later statements is safe on its own (it explicitly isn't).
 */
@Testcontainers
@SpringBootTest
class AdminUserDisableReactivateConcurrencyTest {

  private static final int PAIR_COUNT = 8;
  private static final int ROUNDS = 3;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    // Enough headroom for PAIR_COUNT*2 threads to each hold a connection simultaneously - this
    // test's whole point is genuine row-lock contention, not connection-pool queuing standing in
    // for it and changing the timing this test depends on to provoke real overlap.
    registry.add(
        "spring.datasource.hikari.maximum-pool-size", () -> String.valueOf(PAIR_COUNT * 2 + 5));
  }

  @Autowired AppUserRepository appUserRepository;
  @Autowired AdminUserService adminUserService;

  @Test
  void concurrentDisableAndReactivateOnTheSameTargetNeverDeadlocks() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      raceOneRound(round);
    }
  }

  private void raceOneRound(int round) throws InterruptedException, TimeoutException {
    AppUser target = new AppUser();
    target.setEmail("target-round-" + round + "@example.com");
    target.setPasswordHash("irrelevant-for-this-test");
    target.setRole("STANDARD_USER");
    target.setLanguage("EN");
    UUID targetId = appUserRepository.save(target).getId();

    // A real, persisted user - admin_audit_log.actor_user_id has an FK to app_user, so a
    // fabricated id would fail that constraint on the very first writeAuditLog() call. Role/
    // permission checks are SecurityConfig's job, already bypassed by calling the service
    // directly, so this doesn't need to actually be a SYSTEM_ADMINISTRATOR.
    AppUser actor = new AppUser();
    actor.setEmail("actor-round-" + round + "@example.com");
    actor.setPasswordHash("irrelevant-for-this-test");
    actor.setRole("SYSTEM_ADMINISTRATOR");
    actor.setLanguage("EN");
    UUID actorId = appUserRepository.save(actor).getId();

    CountDownLatch ready = new CountDownLatch(PAIR_COUNT * 2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(PAIR_COUNT * 2);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < PAIR_COUNT; i++) {
        futures.add(
            executor.submit(
                raceTask(ready, go, () -> adminUserService.disableUser(targetId, actorId))));
        futures.add(
            executor.submit(
                raceTask(ready, go, () -> adminUserService.reactivateUser(targetId, actorId))));
      }

      if (!ready.await(10, TimeUnit.SECONDS)) {
        // Interrupt and abandon every still-parked thread now, rather than falling through to
        // go.countDown() below - if not every thread even reached the start line, releasing the
        // ones that did would let them run unsupervised against a Postgres instance a later
        // round (or test class, given Testcontainers-per-class lifecycle) might depend on being
        // in a clean state.
        executor.shutdownNow();
        fail("every racing thread must reach the start line before any is released");
      }
      go.countDown();

      for (Future<?> future : futures) {
        assertRaceOutcomeIsNeverADeadlock(future);
      }
    } finally {
      executor.shutdown();
    }
  }

  // Both a clean completion and a ResponseStatusException are legitimate outcomes of genuinely
  // racing disableUser() against reactivateUser() on the same target (e.g. the last-active-
  // administrator guard, or simply neither call failing at all) - already-tested business
  // behavior, not what this test guards. Only a ConcurrencyFailureException (Spring's translation
  // of Postgres' deadlock_detected, SQLSTATE 40P01) means the serialization the fix provides
  // broke.
  private void assertRaceOutcomeIsNeverADeadlock(Future<?> future)
      throws InterruptedException, TimeoutException {
    try {
      future.get(20, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      assertThat(e.getCause())
          .as(
              "disableUser() and reactivateUser() must never deadlock when racing the same"
                  + " target user - see #62")
          .isNotInstanceOf(ConcurrencyFailureException.class)
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  private static Runnable raceTask(CountDownLatch ready, CountDownLatch go, Runnable action) {
    return () -> {
      ready.countDown();
      awaitUninterruptibly(go);
      action.run();
    };
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await(15, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}

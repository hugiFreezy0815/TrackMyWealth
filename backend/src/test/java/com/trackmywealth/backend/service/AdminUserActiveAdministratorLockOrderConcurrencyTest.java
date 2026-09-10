package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AppUserRepository;
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
 * Regression test for #62's follow-up finding: an earlier version of the #62 fix locked {@link
 * AdminUserService#disableUser}'s target row via a standalone statement, then - only if that target
 * turned out to be a currently active {@code SYSTEM_ADMINISTRATOR} - separately locked every active
 * administrator via {@link AppUserRepository#countActiveAdministratorsForUpdate} for the FR-USR-005
 * last-active-administrator guard. Two concurrent {@code disableUser} calls on *different*
 * active-administrator targets could then each hold their own target's lock first and deadlock
 * reaching for the other's - a classic two-step, opposite-order lock acquisition, confirmed against
 * a real Postgres instance during code review of the original fix.
 *
 * <p>Closed by {@link AppUserRepository#lockTargetAndActiveAdministrators}: {@code disableUser} now
 * locks its target and every active administrator in one {@code ORDER BY id FOR UPDATE} statement,
 * so there is no longer a separate, independently-ordered first lock to race against. {@code
 * countActiveAdministratorsForUpdate} (still used by {@code editUser}'s demote path) got the same
 * {@code ORDER BY id} for the same reason - two different queries locking overlapping rows must
 * still agree on acquisition order, or the same class of deadlock resurfaces across *different* SQL
 * text, not just identical calls.
 *
 * <p>Unlike {@link AdminUserDisableReactivateConcurrencyTest} (which needs many concurrent writers
 * to the *same* row to reproduce its deadlock), this one is the classic AB-BA shape and reproduces
 * reliably with a single racing pair per round - confirmed empirically: reverting {@code
 * lockTargetAndActiveAdministrators} back to a standalone {@code findByIdForUpdate} plus the
 * existing {@code countActiveAdministratorsForUpdate} call reliably deadlocked this test on the
 * very first round.
 */
@Testcontainers
@SpringBootTest
class AdminUserActiveAdministratorLockOrderConcurrencyTest {

  private static final int ROUNDS = 15;

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
  }

  @Autowired AppUserRepository appUserRepository;
  @Autowired AdminUserService adminUserService;

  @Test
  void concurrentDisableOfTwoDifferentActiveAdministratorsNeverDeadlocks() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      raceOneRound(round);
    }
  }

  private void raceOneRound(int round) throws InterruptedException, TimeoutException {
    UUID x = newActiveAdministrator("x-round-" + round);
    UUID y = newActiveAdministrator("y-round-" + round);
    // A third active administrator so disabling either X or Y is legitimately allowed
    // (FR-USR-005 only blocks dropping below one remaining) - both calls should succeed cleanly,
    // not merely "fail without deadlocking."
    newActiveAdministrator("z-round-" + round);
    UUID actor = newActiveAdministrator("actor-round-" + round);

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> disableX =
          executor.submit(raceTask(ready, go, () -> adminUserService.disableUser(x, actor)));
      Future<?> disableY =
          executor.submit(raceTask(ready, go, () -> adminUserService.disableUser(y, actor)));

      if (!ready.await(10, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        throw new AssertionError(
            "every racing thread must reach the start line before any is released");
      }
      go.countDown();

      assertRaceOutcomeIsNeverADeadlock(disableX);
      assertRaceOutcomeIsNeverADeadlock(disableY);
    } finally {
      executor.shutdown();
    }
  }

  // A clean completion is the only truly expected outcome here (three active administrators
  // exist, so disabling any one of them is always allowed) - a ResponseStatusException is
  // tolerated defensively in case of an unrelated, already-tested business rejection, but only a
  // ConcurrencyFailureException (Spring's translation of Postgres' deadlock_detected) means the
  // lock-order invariant this test guards broke.
  private void assertRaceOutcomeIsNeverADeadlock(Future<?> future)
      throws InterruptedException, TimeoutException {
    try {
      future.get(15, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      assertThat(e.getCause())
          .as(
              "disableUser() calls for two different active administrators must never deadlock"
                  + " racing each other - see #62's follow-up finding")
          .isNotInstanceOf(ConcurrencyFailureException.class)
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  private UUID newActiveAdministrator(String label) {
    AppUser user = new AppUser();
    user.setEmail(label + "@example.com");
    user.setPasswordHash("irrelevant-for-this-test");
    user.setRole("SYSTEM_ADMINISTRATOR");
    user.setLanguage("EN");
    return appUserRepository.save(user).getId();
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

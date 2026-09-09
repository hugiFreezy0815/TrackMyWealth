package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.RefreshTokenRepository;
import com.trackmywealth.backend.repository.UserSessionRepository;
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
 * Regression test for #53: {@link TokenRotationService#rotate} and {@link
 * SessionService#revokeSession} both lock a session's {@code refresh_token} row and its {@code
 * user_session} row, and must acquire those locks in the same order (refresh_token, then
 * user_session) to avoid a Postgres deadlock - the exact bug PR #52 fixed. Nothing else in the
 * codebase - no shared helper, no compile-time check - catches a future change that reorders either
 * method's statements, since the resulting deadlock is timing-dependent and invisible to a
 * deterministic, single-threaded test.
 *
 * <p>Runs many concurrent (rotate, revoke) pairs, each pair racing on its own session/token, with
 * every thread released simultaneously via a {@link CountDownLatch} to maximize the chance that at
 * least one pair's two threads genuinely overlap at the vulnerable moment. With the current,
 * correctly-ordered code this can never deadlock regardless of timing, for these two methods
 * specifically - a consistent lock order between exactly these two call paths is a textbook
 * sufficient condition for deadlock-freedom between them (whichever thread reaches {@code
 * refresh_token} first simply makes the other wait for it there, so neither can ever be found
 * holding {@code user_session} while blocked on {@code refresh_token}). So this test's real job is
 * to fail the moment that invariant is broken again for rotate()/revokeSession(), not to prove it
 * holds today - it does not assert *how* the two methods order their statements, only that racing
 * them repeatedly never produces the failure mode that ordering exists to prevent.
 *
 * <p><b>Scope, precisely:</b> this guards only {@code rotate()} and {@code revokeSession()} - the
 * two methods #53 named. It is not a claim that every transaction touching {@code refresh_token}
 * and {@code user_session} is mutually deadlock-free: {@code AdminUserService.disableUser()} and
 * {@code reactivateUser()} also touch both tables, in an order that mismatches between the two of
 * them, and a concurrency probe built the same way as this test confirmed a real (if differently
 * shaped - see #62) deadlock racing those two on the same target user. That gap is tracked
 * separately in #62 rather than folded into this class, since its actual mechanism turned out to
 * involve contention on the shared {@code app_user} row as well, not just {@code
 * refresh_token}/{@code user_session} ordering, and deserves its own properly-scoped fix rather
 * than a rushed one bolted on here.
 */
@Testcontainers
@SpringBootTest
class TokenRotationSessionRevocationConcurrencyTest {

  private static final int PAIR_COUNT = 15;
  private static final int ROUNDS = 3;
  private static final String ADMIN_EMAIL = "admin@example.com";
  private static final String PASSWORD = "correct-horse-battery-staple";

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
  @Autowired RefreshTokenRepository refreshTokenRepository;
  @Autowired UserSessionRepository userSessionRepository;
  @Autowired SetupService setupService;
  @Autowired TokenIssuanceService tokenIssuanceService;
  @Autowired TokenRotationService tokenRotationService;
  @Autowired SessionService sessionService;
  @Autowired TokenHashingService tokenHashingService;

  @Test
  void concurrentRotateAndRevokeOnTheSameSessionNeverDeadlocks() throws Exception {
    setupService.bootstrapInitialAdministrator(
        new SetupAdministratorRequest(ADMIN_EMAIL, PASSWORD, "Test Workspace", "CHF"),
        "seed-device",
        null);
    AppUser admin = appUserRepository.findAll().get(0);

    for (int round = 0; round < ROUNDS; round++) {
      raceOneRound(admin, round);
    }
  }

  private void raceOneRound(AppUser admin, int round)
      throws InterruptedException, TimeoutException {
    List<SessionAndToken> pairs = new ArrayList<>();
    for (int i = 0; i < PAIR_COUNT; i++) {
      pairs.add(issueSessionAndToken(admin, "round-" + round + "-pair-" + i));
    }

    CountDownLatch ready = new CountDownLatch(pairs.size() * 2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(pairs.size() * 2);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (SessionAndToken pair : pairs) {
        futures.add(
            executor.submit(
                raceTask(ready, go, () -> tokenRotationService.rotate(pair.refreshToken()))));
        futures.add(
            executor.submit(
                raceTask(
                    ready,
                    go,
                    () ->
                        sessionService.revokeSession(
                            pair.sessionId(), admin.getId(), UUID.randomUUID()))));
      }

      if (!ready.await(10, TimeUnit.SECONDS)) {
        // Interrupt and abandon every still-parked thread now, rather than falling through to
        // go.countDown() below - if not every thread even reached the start line, releasing the
        // ones that did would let them run unsupervised (the test is about to fail regardless)
        // against a Postgres instance the next round, or a later test class sharing the
        // Testcontainers-per-class lifecycle, might still depend on being in a clean state.
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
  // racing rotate() against revoke() on the same session (e.g. rotate() losing the race because
  // revoke() already invalidated the token it was about to rotate) - already-tested business
  // behavior, not what this test guards. Only a ConcurrencyFailureException (Spring's translation
  // of Postgres' deadlock_detected, SQLSTATE 40P01) means the lock-order invariant broke.
  private void assertRaceOutcomeIsNeverADeadlock(Future<?> future)
      throws InterruptedException, TimeoutException {
    try {
      future.get(20, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      assertThat(e.getCause())
          .as(
              "rotate() and revokeSession() must never deadlock when racing the same session's"
                  + " refresh_token/user_session rows - see #53")
          .isNotInstanceOf(ConcurrencyFailureException.class)
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  private SessionAndToken issueSessionAndToken(AppUser admin, String deviceLabel) {
    AuthTokensResponse tokens = tokenIssuanceService.issueTokens(admin, deviceLabel, null);
    UUID sessionId =
        refreshTokenRepository
            .findByTokenHash(tokenHashingService.sha256Hex(tokens.refreshToken()))
            .flatMap(
                refreshToken -> userSessionRepository.findByRefreshToken_Id(refreshToken.getId()))
            .orElseThrow()
            .getId();
    return new SessionAndToken(sessionId, tokens.refreshToken());
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

  private record SessionAndToken(UUID sessionId, String refreshToken) {}
}

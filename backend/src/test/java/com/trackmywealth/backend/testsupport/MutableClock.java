package com.trackmywealth.backend.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A controllable "now" in place of the application's real {@link Clock} ({@code ClockConfig}),
 * shared by every test that needs deterministic time rather than depending on whenever the suite
 * happens to run: TOTP replay protection in {@code MfaControllerTest} (which {@link #advance}s it
 * in fixed steps) and the current-period computation in {@code CardStatementControllerTest} (which
 * {@link #set}s it to an explicit business date).
 *
 * <p>{@link #withZone} answers with a snapshot of the current instant fixed to the requested zone,
 * computed fresh on each call - so a caller that reads "today" through a zone-applying service
 * (e.g. {@code BusinessDateService}) sees a correct, up-to-date date, not just a correct instant.
 */
public class MutableClock extends Clock {

  private volatile Instant now;

  public MutableClock() {
    this(Instant.parse("2026-01-01T00:00:00Z"));
  }

  public MutableClock(Instant initial) {
    this.now = initial;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return Clock.fixed(now, zone);
  }

  @Override
  public Instant instant() {
    return now;
  }

  public void set(Instant instant) {
    this.now = instant;
  }

  public void advance(Duration duration) {
    this.now = this.now.plus(duration);
  }
}

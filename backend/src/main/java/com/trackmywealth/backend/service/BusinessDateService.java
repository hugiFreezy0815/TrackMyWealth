package com.trackmywealth.backend.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * "What is today's date, for the people this deployment serves" - the one source of the as-of date
 * for every date-filtered read (US-09-01's ledger balance, the institution summary, net worth).
 *
 * <p>The JVM's own default zone is deliberately not used: a container defaults to UTC, so between
 * local midnight and 01:00/02:00 in Switzerland/Germany "today" on the server is still yesterday
 * for the user. A purchase booked with the user's local date would then be future-dated to the
 * server and left out of the balance until the server's date caught up. The zone is deployment
 * configuration ({@code app.business-zone}), defaulting to {@code Europe/Zurich}, which has the
 * same offsets as Germany's zone. Time comes from the shared {@link Clock} bean so a test can
 * control it.
 */
@Service
public class BusinessDateService {

  private final Clock clock;
  private final ZoneId zone;

  public BusinessDateService(Clock clock, @Value("${app.business-zone}") String zone) {
    this.clock = clock;
    // ZoneId.of fails fast at startup on a typo rather than on the first balance read.
    this.zone = ZoneId.of(zone);
  }

  public LocalDate today() {
    return LocalDate.now(clock.withZone(zone));
  }
}

package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.TrackMyWealthApplication;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * US-29-02 (#372): the application started in a JVM of its own - same classpath and test
 * configuration, this JVM's encoding, time zone and locale, the calling test's database, its Quartz
 * scheduler not started - to fetch one document from it and stop it again. Its output goes to
 * {@code target/fresh-jvm-application.log}, quoted when it does not come up.
 */
final class FreshJvmApplication {

  private static final Duration STARTUP = Duration.ofMinutes(3);
  private static final Duration POLL = Duration.ofMillis(500);
  private static final Path LOG = Path.of("target", "fresh-jvm-application.log");

  // What a document could depend on besides the code, taken over from this JVM.
  private static final List<String> FORWARDED_PROPERTIES =
      List.of("file.encoding", "user.timezone", "user.language", "user.country");

  private FreshJvmApplication() {}

  /** The body the fresh application answers {@code GET path} with, as {@code accessToken}. */
  static String serve(PostgreSQLContainer<?> database, String path, String accessToken)
      throws IOException, InterruptedException {
    int port = freePort();
    Files.createDirectories(LOG.getParent());
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    for (String property : FORWARDED_PROPERTIES) {
      String value = System.getProperty(property);
      if (value != null && !value.isEmpty()) {
        command.add("-D" + property + "=" + value);
      }
    }
    command.addAll(
        List.of(
            "-cp",
            System.getProperty("java.class.path"),
            TrackMyWealthApplication.class.getName(),
            "--server.port=" + port,
            "--spring.datasource.url=" + database.getJdbcUrl(),
            "--spring.datasource.username=" + database.getUsername(),
            "--spring.datasource.password=" + database.getPassword(),
            "--app.rate-limit.enabled=false",
            // The scheduler never starts: it would join the calling test's Quartz cluster under
            // the same instance id and could recover (take over) that scheduler's triggers.
            "--spring.quartz.auto-startup=false"));
    Process application =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(LOG.toFile()).start();
    try {
      return fetch(application, URI.create("http://localhost:" + port + path), accessToken);
    } finally {
      application.destroy();
      if (!application.waitFor(30, TimeUnit.SECONDS)) {
        application.destroyForcibly();
      }
    }
  }

  private static String fetch(Process application, URI uri, String accessToken)
      throws IOException, InterruptedException {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .header("Authorization", "Bearer " + accessToken)
            .timeout(Duration.ofSeconds(30))
            .build();
    Instant deadline = Instant.now().plus(STARTUP);
    while (application.isAlive() && Instant.now().isBefore(deadline)) {
      try {
        HttpResponse<String> response =
            client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 200) {
          return response.body();
        }
        throw new AssertionError(
            "The fresh JVM answered " + uri + " with " + response.statusCode() + logTail());
      } catch (IOException notServingYet) {
        // Refused or reset while the application starts; anything lasting ends at the deadline.
        Thread.sleep(POLL.toMillis());
      }
    }
    throw new AssertionError(
        "The fresh JVM did not serve "
            + uri
            + (application.isAlive() ? " within " + STARTUP : " - it exited")
            + logTail());
  }

  private static String logTail() throws IOException {
    List<String> lines = Files.readAllLines(LOG, StandardCharsets.UTF_8);
    return "; last lines of "
        + LOG
        + ":\n"
        + String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}

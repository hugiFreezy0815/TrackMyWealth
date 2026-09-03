package com.trackmywealth.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the TrackMyWealth API.
 *
 * <p>Startup sequence relevant to database provisioning (see
 * docs/architecture/adr/0001-database-auto-migration.md for the full rationale):
 * <ol>
 *   <li>{@link com.trackmywealth.backend.config.DatabaseBootstrapInitializer} runs as an
 *       {@code ApplicationContextInitializer}, before any {@code DataSource} bean is created,
 *       and creates the target PostgreSQL database if it does not already exist.</li>
 *   <li>Spring Boot's Flyway auto-configuration then runs every migration under
 *       {@code src/main/resources/db/migration} that has not yet been applied - on a brand new
 *       database that is all of them (full schema creation); on an existing database it is only
 *       whatever has been added since the last deployment (incremental schema evolution).</li>
 *   <li>Hibernate starts in {@code ddl-auto: validate} mode and never mutates the schema itself.</li>
 * </ol>
 * No manual migration step is required in any environment, self-hosted or hosted.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class TrackMyWealthApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrackMyWealthApplication.class, args);
    }
}

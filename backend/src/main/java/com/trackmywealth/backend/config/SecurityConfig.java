package com.trackmywealth.backend.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Placeholder security posture until EPIC-02 (login/JWT) and EPIC-28 (tenancy) are implemented:
 * only the liveness/readiness surface ({@code /actuator/health}, {@code /actuator/info}) is public;
 * everything else requires authentication. Without an explicit {@link SecurityFilterChain} bean,
 * Spring Boot's default posture also requires authentication for actuator endpoints, which would
 * make health checks unusable before any login mechanism exists.
 *
 * <p>CORS is configured here because the web build of {@code mobile/} (an Expo Router app exported
 * for web, see its README) calls this API from a browser on a different origin - unlike the
 * iOS/Android builds, which are not subject to the browser's same-origin policy at all. The allowed
 * origins are deployment configuration ({@code app.cors.allowed-origins}, comma-separated, env
 * {@code CORS_ALLOWED_ORIGINS}), not hardcoded, so a self-hosted deployment can point this at
 * wherever it serves its own web build.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Value("${app.cors.allowed-origin-patterns:http://localhost:*}")
  private List<String> allowedOriginPatterns;

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        // Stateless, token-based API (see EPIC-02) - no session cookie for CSRF to protect.
        .csrf(csrf -> csrf.disable());
    return http.build();
  }

  private CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOriginPatterns(allowedOriginPatterns);
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    // Bearer tokens are sent via the Authorization header (see mobile/src/api/client.ts), not
    // cookies, so credentialed (cookie-carrying) CORS requests are not needed yet. Revisit
    // if/when EPIC-02's web refresh-token cookie (FR-AUT-006) is implemented.
    configuration.setAllowCredentials(false);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}

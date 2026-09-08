package com.trackmywealth.backend.config;

import com.trackmywealth.backend.security.JwtAuthenticationFilter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Public surface: the liveness/readiness endpoints, the one-time setup bootstrap ({@code
 * /api/v1/setup/**}, US-01-03 - which must be reachable before any credential exists, and rejects
 * itself once one does, see {@code SetupService}), login/refresh ({@code /api/v1/auth/**}, US-02-02
 * - must be reachable by a caller who has no token yet), and {@code /error}. Everything else
 * requires a valid {@link JwtAuthenticationFilter}-authenticated request; {@code /api/v1/admin/**}
 * additionally requires the {@code SYSTEM_ADMINISTRATOR} role (US-02-01).
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
  SecurityFilterChain filterChain(
      HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
    http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .requestMatchers("/api/v1/setup/**")
                    .permitAll()
                    .requestMatchers("/api/v1/auth/**")
                    .permitAll()
                    // Spring MVC's default handling of a thrown ResponseStatusException (e.g.
                    // US-01-03's "setup already completed" 409) forwards internally to /error to
                    // render the response body. Spring Security re-secures that forwarded
                    // dispatch by default; without this, an anonymous caller's intended error
                    // response gets silently replaced with a bare 403 from
                    // Http403ForbiddenEntryPoint before it ever reaches BasicErrorController.
                    .requestMatchers("/error")
                    .permitAll()
                    // FR-TEN-007: administration rights are their own permission domain -
                    // gated on role alone here, deliberately never on workspace context.
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("SYSTEM_ADMINISTRATOR")
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        // A missing/invalid/expired token gets a plain 401, distinct from the 403 an
        // authenticated-but-wrong-role caller gets (Spring Security's own AccessDeniedHandler,
        // unchanged) - without this, both cases fall back to the same Http403ForbiddenEntryPoint,
        // which is the entry point ordinarily meant for a session/form-login flow, not a
        // stateless token API where a client needs to tell "log in" apart from "not allowed".
        .exceptionHandling(
            exceptions ->
                exceptions.authenticationEntryPoint(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        // Stateless, token-based API (see EPIC-02) - no session cookie for CSRF to protect.
        .csrf(csrf -> csrf.disable());
    return http.build();
  }

  // FR-AUT-007: a modern memory-hard hash with a per-user salt. Argon2id specifically (not
  // Argon2i/d) is Spring Security's default for this factory method, and is the OWASP-recommended
  // variant. Requires org.bouncycastle:bcprov-jdk18on on the classpath.
  @Bean
  PasswordEncoder passwordEncoder() {
    return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
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

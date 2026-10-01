package com.trackmywealth.backend.config;

import com.trackmywealth.backend.controller.SecurityController;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.security.JwtAuthenticationFilter;
import com.trackmywealth.backend.security.RateLimitFilter;
import com.trackmywealth.backend.web.CorrelationIdFilter;
import com.trackmywealth.backend.web.ProblemResponseWriter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Public surface: the liveness/readiness endpoints, the one-time setup bootstrap ({@code
 * /api/v1/setup/**}, US-01-03 - which must be reachable before any credential exists, and rejects
 * itself once one does, see {@code SetupService}), login/refresh ({@code /api/v1/auth/**}, US-02-02
 * - must be reachable by a caller who has no token yet), and {@code /error}. Everything else
 * requires a valid {@link JwtAuthenticationFilter}-authenticated request; {@code /api/v1/admin/**}
 * and every Actuator endpoint other than health and info (e.g. Flyway's migration metadata)
 * additionally require the {@code SYSTEM_ADMINISTRATOR} role (US-02-01, NFR-SEC-001).
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
      HttpSecurity http,
      JwtAuthenticationFilter jwtAuthenticationFilter,
      RateLimitFilter rateLimitFilter,
      ProblemResponseWriter problemResponseWriter)
      throws Exception {
    http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    // NFR-SEC-001 / #191: of the Actuator, only health (with its probe groups)
                    // and info are public, for deployment probes; health's details are shown to
                    // administrators only (management.endpoint.health.roles). Every other
                    // endpoint - Flyway's schema-evolution metadata, the /actuator index, and any
                    // endpoint a deployment exposes later - is an administrator-only operational
                    // surface. EndpointRequest follows a changed base path or management port.
                    .requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class))
                    .permitAll()
                    .requestMatchers(EndpointRequest.toAnyEndpoint())
                    .hasRole("SYSTEM_ADMINISTRATOR")
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
        // FR-AUT-010: rejected before it costs a JWT parse or a findAuthSnapshot query, not just
        // before the endpoint's own business logic. JwtAuthenticationFilter's own position must
        // already be registered (the call above) before another filter can be placed relative to
        // it - Spring Security's filter comparator resolves this per call, not by final order.
        .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)
        // A missing/invalid/expired token gets a plain 401, distinct from the 403 an
        // authenticated-but-wrong-role caller gets (Spring Security's own AccessDeniedHandler,
        // unchanged) - without this, both cases fall back to the same Http403ForbiddenEntryPoint,
        // which is the entry point ordinarily meant for a session/form-login flow, not a
        // stateless token API where a client needs to tell "log in" apart from "not allowed".
        // EPIC-29 (#149): both in the one error shape, with their own stable codes.
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(
                        (request, response, denied) ->
                            problemResponseWriter.write(
                                request,
                                response,
                                HttpStatus.UNAUTHORIZED,
                                ApiErrorCode.UNAUTHENTICATED,
                                "Sign in to continue."))
                    .accessDeniedHandler(
                        (request, response, denied) ->
                            problemResponseWriter.write(
                                request,
                                response,
                                HttpStatus.FORBIDDEN,
                                ApiErrorCode.FORBIDDEN,
                                "Not allowed.")))
        // Stateless, token-based API (see EPIC-02) - no session cookie for CSRF to protect.
        .csrf(csrf -> csrf.disable());
    return http.build();
  }

  // EPIC-29 (#149): a request the security firewall refuses (a ";" or an encoded "." or "/" in the
  // path) is answered in the one error shape too. Without this bean it is rethrown to the container
  // and rendered by /error instead; picked up by WebSecurity from the context.
  @Bean
  RequestRejectedHandler requestRejectedHandler(ProblemResponseWriter problemResponseWriter) {
    return (request, response, rejected) ->
        problemResponseWriter.write(
            request,
            response,
            HttpStatus.BAD_REQUEST,
            ApiErrorCode.VALIDATION_FAILED,
            "The request was rejected.");
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
    configuration.setAllowedHeaders(
        List.of(
            "Authorization",
            "Content-Type",
            CorrelationIdFilter.HEADER,
            HttpHeaders.IF_MATCH));
    // Bearer tokens are sent via the Authorization header (see mobile/src/api/client.ts), not
    // cookies, so credentialed (cookie-carrying) CORS requests are not needed yet. Revisit
    // if/when EPIC-02's web refresh-token cookie (FR-AUT-006) is implemented.
    configuration.setAllowCredentials(false);
    // A browser hides every response header from JS except the CORS-safelisted six unless the
    // server names it here - so without this, the web build of mobile/ reads null for a header the
    // iOS/Android builds (not subject to the same-origin policy) read fine. Any custom response
    // header this API adds must be listed, or it is invisible on exactly one of the three clients.
    configuration.setExposedHeaders(
        List.of(SecurityController.IGNORED_FIELDS_HEADER, CorrelationIdFilter.HEADER));

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}

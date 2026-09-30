package com.trackmywealth.backend.web;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * EPIC-29 (#149): registers {@link CorrelationIdFilter} first, for the request itself and for the
 * container's error and async dispatches. Spring Boot would otherwise register the filter bean for
 * {@code REQUEST} only, and an error rendered by {@code /error} (see {@code
 * ProblemErrorController}) would carry no correlation id.
 */
@Configuration
public class CorrelationIdFilterRegistration {

  @Bean
  FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterOnEveryDispatch(
      CorrelationIdFilter filter) {
    FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setDispatcherTypes(
        DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}

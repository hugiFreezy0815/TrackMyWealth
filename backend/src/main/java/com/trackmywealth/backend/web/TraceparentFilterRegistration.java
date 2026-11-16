package com.trackmywealth.backend.web;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
class TraceparentFilterRegistration {

  @Bean
  FilterRegistrationBean<TraceparentResponseFilter> traceparentFilterOnRequestAndError(
      TraceparentResponseFilter filter) {
    var registration = new FilterRegistrationBean<>(filter);
    registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
    // Spring Boot opens the server observation at HIGHEST_PRECEDENCE + 1.
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
    return registration;
  }
}

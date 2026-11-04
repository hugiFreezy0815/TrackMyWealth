package com.trackmywealth.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.core.Ordered;

/**
 * US-29-02 (#372): an {@link OpenApiCustomizer} with a place in the order springdoc runs them in.
 *
 * <p>springdoc sorts the customizer <em>instances</em> with Spring's order comparator, so an
 * {@code @Order} on the {@code @Bean} method of a lambda customizer is never seen: the lambda
 * carries no annotation and gets {@link Ordered#LOWEST_PRECEDENCE}, like every unordered one.
 * Implementing {@link Ordered} on the instance is what springdoc reads.
 */
record OrderedOpenApiCustomizer(int order, OpenApiCustomizer customizer)
    implements OpenApiCustomizer, Ordered {

  @Override
  public void customise(OpenAPI openApi) {
    customizer.customise(openApi);
  }

  @Override
  public int getOrder() {
    return order;
  }
}

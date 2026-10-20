package com.trackmywealth.backend.config;

import com.trackmywealth.backend.web.PagedArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the paging contract's resolver (#373). Ordered first so it comes before Spring Data's
 * own {@code Pageable} resolver, which is registered the same way and would otherwise answer for a
 * {@link com.trackmywealth.backend.web.Paged} parameter too.
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebMvcConfig implements WebMvcConfigurer {

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new PagedArgumentResolver());
  }
}

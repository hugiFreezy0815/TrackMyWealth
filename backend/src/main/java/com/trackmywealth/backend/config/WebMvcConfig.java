package com.trackmywealth.backend.config;

import com.trackmywealth.backend.web.DeprecationHeaders;
import com.trackmywealth.backend.web.PagedArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the paging contract's resolver (#373). Ordered first so it comes before Spring Data's
 * own {@code Pageable} resolver, which is registered the same way and would otherwise answer for a
 * {@link com.trackmywealth.backend.web.Paged} parameter too.
 *
 * <p>Also registers the deprecation headers (#576) for every handler.
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebMvcConfig implements WebMvcConfigurer {

  private final DeprecationHeaders deprecationHeaders;

  public WebMvcConfig(DeprecationHeaders deprecationHeaders) {
    this.deprecationHeaders = deprecationHeaders;
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new PagedArgumentResolver());
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(deprecationHeaders);
  }
}

package com.trackmywealth.backend.config;

import com.trackmywealth.backend.web.DeprecationHeaders;
import com.trackmywealth.backend.web.PagedArgumentResolver;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.List;
import org.springframework.context.annotation.Bean;
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

  /**
   * How many app versions the deprecation counter keeps apart; calls with further versions are not
   * counted. The version comes from the client, so without a cap a caller could grow the meter
   * registry without bound.
   */
  static final int MAX_COUNTED_APP_VERSIONS = 100;

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

  @Bean
  static MeterFilter deprecatedRequestVersionCap() {
    return MeterFilter.maximumAllowableTags(
        DeprecationHeaders.METRIC,
        DeprecationHeaders.APP_VERSION_TAG,
        MAX_COUNTED_APP_VERSIONS,
        MeterFilter.deny());
  }
}

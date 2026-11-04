package com.trackmywealth.backend.testsupport;

import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * US-29-02 (#372): registers {@link DocumentedSuccessStatusFilter} in every Spring test context
 * (listed in {@code META-INF/spring.factories} of the test resources), so each request a test makes
 * against the running application is checked - no test class has to opt in.
 */
public class DocumentedSuccessStatusCheck implements ContextCustomizerFactory {

  @Override
  public ContextCustomizer createContextCustomizer(
      Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
    return new RegisterFilter();
  }

  /** Stateless, so equal for every test class: it never splits the context cache. */
  private record RegisterFilter() implements ContextCustomizer {

    @Override
    public void customizeContext(
        ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
      context
          .getBeanFactory()
          .registerSingleton("documentedSuccessStatusFilter", new DocumentedSuccessStatusFilter());
    }
  }
}

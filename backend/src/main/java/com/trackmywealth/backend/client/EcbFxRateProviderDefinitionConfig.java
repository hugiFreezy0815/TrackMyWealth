package com.trackmywealth.backend.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provider metadata that is present even when the ECB client itself is not selected. Keeping this
 * separate from the conditional client lets historical ECB rows retain their EUR hub after another
 * provider becomes active. It lives next to the client rather than in {@code config} because the
 * client already reads its settings from {@code config}: the reverse dependency would make the two
 * packages a cycle (#226).
 */
@Configuration(proxyBeanMethods = false)
public class EcbFxRateProviderDefinitionConfig {

  @Bean
  FxRateProviderDefinition ecbFxRateProviderDefinition() {
    return EcbFxRateProvider.ECB_DEFINITION;
  }
}

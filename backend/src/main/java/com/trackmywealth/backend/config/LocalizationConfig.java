package com.trackmywealth.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;

/** Web-layer internationalization infrastructure for caller-visible validation errors (#153). */
@Configuration
public class LocalizationConfig {

  @Bean
  LocaleResolver localeResolver() {
    return new RequestLocaleResolver();
  }
}

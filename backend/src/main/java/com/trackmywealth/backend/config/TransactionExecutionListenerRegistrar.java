package com.trackmywealth.backend.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * No Spring Boot autoconfiguration wires {@link TransactionExecutionListener} beans into the
 * transaction manager on its own (unlike, say, {@code HandlerInterceptor} beans and Spring MVC) -
 * this registers every one found in the context, {@link
 * HouseholdContextTransactionExecutionListener} included, onto the auto-configured {@link
 * JpaTransactionManager} specifically, not every {@code AbstractPlatformTransactionManager} that
 * might exist. {@link HouseholdContextTransactionExecutionListener} always issues its {@code
 * set_config} against its own injected {@code DataSource}, so attaching it to some other, future
 * transaction manager backed by a different data source would silently set the household context on
 * an unrelated connection instead of the active transaction's own - scoping the match this narrowly
 * is what keeps that impossible rather than merely unlikely.
 */
@Configuration
public class TransactionExecutionListenerRegistrar {

  // Static, and depending only on an ObjectProvider parameter (not a field) - the pattern Spring
  // Boot's own docs call out as the way to inject something into a BeanPostProcessor without
  // forcing this configuration class to be instantiated too early in the container startup
  // sequence.
  @Bean
  static BeanPostProcessor householdContextTransactionExecutionListenerRegistrar(
      ObjectProvider<TransactionExecutionListener> transactionExecutionListeners) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof JpaTransactionManager transactionManager) {
          List<TransactionExecutionListener> merged =
              new ArrayList<>(transactionManager.getTransactionExecutionListeners());
          transactionExecutionListeners.forEach(merged::add);
          transactionManager.setTransactionExecutionListeners(merged);
        }
        return bean;
      }
    };
  }
}

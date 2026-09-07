package com.trackmywealth.backend.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

/**
 * No Spring Boot autoconfiguration wires {@link TransactionExecutionListener} beans into the
 * transaction manager on its own (unlike, say, {@code HandlerInterceptor} beans and Spring MVC) -
 * this registers every one found in the context, {@link
 * HouseholdContextTransactionExecutionListener} included, onto whichever {@link
 * AbstractPlatformTransactionManager} Spring Boot auto-configures (here, {@code
 * JpaTransactionManager}).
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
        if (bean instanceof AbstractPlatformTransactionManager transactionManager) {
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

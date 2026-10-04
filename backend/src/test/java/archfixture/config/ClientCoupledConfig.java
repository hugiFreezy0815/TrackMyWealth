package archfixture.config;

import com.trackmywealth.backend.client.EcbFxRateProvider;

/** Fixture: settings code reaching into the client package, the reverse of its dependency. */
public class ClientCoupledConfig {

  public String defaultProvider() {
    return EcbFxRateProvider.ECB_DEFINITION.name();
  }
}

package archfixture.service;

import com.trackmywealth.backend.client.EcbFxRateProvider;

/** Fixture: a service depending on one concrete FX rate provider instead of the interface. */
public class ProviderCoupledService {

  private final EcbFxRateProvider provider;

  public ProviderCoupledService(EcbFxRateProvider provider) {
    this.provider = provider;
  }

  public String source() {
    return provider.definition().source();
  }
}

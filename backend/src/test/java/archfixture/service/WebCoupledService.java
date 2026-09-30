package archfixture.service;

import com.trackmywealth.backend.web.CorrelationIdFilter;

/** Fixture: a service depending on the application's web layer. */
public class WebCoupledService {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  public CorrelationIdFilter filter() {
    return filter;
  }
}

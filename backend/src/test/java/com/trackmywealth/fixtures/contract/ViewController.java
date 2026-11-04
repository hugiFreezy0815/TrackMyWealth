package com.trackmywealth.fixtures.contract;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * US-29-02 (#372): a {@code @Controller} that renders a view or redirects - not documented by
 * springdoc, so not held to the contract rules - for {@code ApiContractRulesTest}.
 */
@Controller
public class ViewController {

  @GetMapping("/view-fixture")
  public String redirect() {
    return "redirect:/";
  }
}

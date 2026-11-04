package com.trackmywealth.fixtures.contract;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * US-29-02 (#372): a {@code @Controller} with a {@code @ResponseBody} handler - documented by
 * springdoc like a {@code @RestController} - without the contract annotations, for {@code
 * ApiContractRulesTest}.
 */
@Controller
public class UndocumentedPlainController {

  @ResponseBody
  @GetMapping("/plain-fixture")
  public String plainWithoutOperation() {
    return "";
  }
}

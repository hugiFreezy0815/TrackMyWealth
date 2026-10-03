package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.NetWorthResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.NetWorthService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** US-09-01 and US-06-05: workspace net worth with an optional ad-hoc display currency. */
@RestController
@RequestMapping("/api/v1/net-worth")
public class NetWorthController {

  private final NetWorthService netWorthService;

  public NetWorthController(NetWorthService netWorthService) {
    this.netWorthService = netWorthService;
  }

  @GetMapping
  public NetWorthResponse getNetWorth(
      @RequestParam(required = false) String currency,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return netWorthService.getNetWorth(actor, currency);
  }
}

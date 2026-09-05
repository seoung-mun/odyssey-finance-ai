package com.dacon.core.savings;

import com.dacon.core.savings.SavingsDtos.RecommendationListResponse;
import com.dacon.core.savings.SavingsDtos.WhatIfRequest;
import com.dacon.core.savings.SavingsDtos.WhatIfResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/savings")
public class SavingsController {
  private final SavingsRecommendationService service;

  public SavingsController(SavingsRecommendationService service) {
    this.service = service;
  }

  @GetMapping("/recommendations")
  public RecommendationListResponse recommendations(@AuthenticationPrincipal Jwt jwt) {
    return service.recommendations(Integer.parseInt(jwt.getSubject()));
  }

  @PostMapping("/products/{productId}/what-if")
  public WhatIfResponse whatIf(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long productId,
      @Valid @RequestBody WhatIfRequest request) {
    return service.whatIf(Integer.parseInt(jwt.getSubject()), productId, request);
  }
}

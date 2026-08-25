package com.dacon.core.financial;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/financial-profile")
public class FinancialProfileController {
  private final FinancialProfileService service;

  FinancialProfileController(FinancialProfileService service) {
    this.service = service;
  }

  @GetMapping
  FinancialProfileResponse get(@AuthenticationPrincipal Jwt jwt) {
    return service.get(Integer.parseInt(jwt.getSubject()));
  }

  @PutMapping
  FinancialProfileResponse upsert(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FinancialProfileInput input) {
    return service.upsert(Integer.parseInt(jwt.getSubject()), input);
  }
}

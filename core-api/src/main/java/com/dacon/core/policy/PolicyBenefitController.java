package com.dacon.core.policy;

import com.dacon.core.policy.PolicyBenefitDtos.ConfirmPolicyBenefitRequest;
import com.dacon.core.policy.PolicyBenefitDtos.PolicyBenefitResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PolicyBenefitController {
  private final PolicyBenefitService service;

  public PolicyBenefitController(PolicyBenefitService service) {
    this.service = service;
  }

  @PostMapping("/policy-versions/{policyVersionId}/benefits")
  public ResponseEntity<PolicyBenefitResponse> confirm(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long policyVersionId,
      @Valid @RequestBody ConfirmPolicyBenefitRequest input) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.confirm(userId(jwt), policyVersionId, input));
  }

  @GetMapping("/goals/{goalId}/policy-benefits")
  public java.util.List<PolicyBenefitResponse> list(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive int goalId) {
    return service.list(userId(jwt), goalId);
  }

  @DeleteMapping("/policy-benefits/{benefitId}")
  public PolicyBenefitResponse cancel(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long benefitId) {
    return service.cancel(userId(jwt), benefitId);
  }

  private int userId(Jwt jwt) {
    return Integer.parseInt(jwt.getSubject());
  }
}

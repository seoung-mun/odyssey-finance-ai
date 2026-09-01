package com.dacon.core.policy;

import com.dacon.core.error.ApiException;
import com.dacon.core.error.RequestIdFilter;
import com.dacon.core.policy.PolicyDtos.PolicyScenarioRequest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 현재 계획을 바꾸지 않고 확정 정책 지원금만 비교하는 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1/policy-versions")
public class PolicyScenarioController {
  private final PolicyScenarioService service;

  public PolicyScenarioController(PolicyScenarioService service) {
    this.service = service;
  }

  @PostMapping("/{policyVersionId}/scenario")
  public JsonNode compare(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable long policyVersionId,
      @Valid @RequestBody PolicyScenarioRequest request,
      HttpServletRequest servletRequest) {
    if (policyVersionId < 1) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "요청 값을 확인해 주세요.");
    }
    return service.compare(
        Integer.parseInt(jwt.getSubject()),
        policyVersionId,
        request,
        (String) servletRequest.getAttribute(RequestIdFilter.ATTRIBUTE));
  }
}

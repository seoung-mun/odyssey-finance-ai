package com.dacon.core.policy;

import com.dacon.core.policy.PolicyDtos.PolicySearchRequest;
import com.dacon.core.policy.PolicyDtos.PolicySearchResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 저장 없는 정책 질문·검색 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {
  private final PolicySearchService service;

  public PolicyController(PolicySearchService service) {
    this.service = service;
  }

  @PostMapping("/search")
  public PolicySearchResponse search(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PolicySearchRequest request) {
    return service.search(Integer.parseInt(jwt.getSubject()), request);
  }
}

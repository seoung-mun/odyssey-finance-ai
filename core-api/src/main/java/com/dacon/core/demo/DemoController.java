package com.dacon.core.demo;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 데모 선택과 인증 사용자 seed의 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1")
public class DemoController {
  private final DemoService service;

  public DemoController(DemoService service) {
    this.service = service;
  }

  @GetMapping("/demo/testers")
  public List<DemoDtos.DemoTester> testers() {
    return service.testers();
  }

  @PostMapping("/me/demo-seed")
  public DemoDtos.DemoSeedResponse seed(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DemoDtos.DemoSeedRequest input) {
    return service.seed(Integer.parseInt(jwt.getSubject()), input.testerId());
  }
}

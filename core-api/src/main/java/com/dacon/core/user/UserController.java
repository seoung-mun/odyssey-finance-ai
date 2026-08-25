package com.dacon.core.user;

import com.dacon.core.user.dto.FinancialProfileInput;
import com.dacon.core.user.dto.FinancialProfileResponse;
import com.dacon.core.user.dto.UserDtos.MeResponse;
import com.dacon.core.user.dto.UserDtos.ProfileInput;
import com.dacon.core.user.dto.UserDtos.ProfileResponse;
import com.dacon.core.user.dto.UserDtos.SampleResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 현재 사용자와 온보딩 HTTP API를 제공한다. */
@RestController
@RequestMapping("/api/v1")
public class UserController {
  private final UserService service;

  /** 계정 유스케이스 서비스를 받는다. */
  public UserController(UserService service) {
    this.service = service;
  }

  /** JWT subject의 현재 사용자 상태를 조회한다. */
  @GetMapping("/me")
  public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
    return service.me(userId(jwt));
  }

  /** JWT subject의 인적 프로필을 조회한다. */
  @GetMapping("/me/profile")
  public ProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
    return service.profile(userId(jwt));
  }

  /** JWT subject의 인적 프로필을 저장한다. */
  @PutMapping("/me/profile")
  public ProfileResponse upsertProfile(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileInput input) {
    return service.upsertProfile(userId(jwt), input);
  }

  /** JWT subject의 빈 계정에 샘플을 적재한다. */
  @PostMapping("/me/sample-data")
  public SampleResponse loadSample(@AuthenticationPrincipal Jwt jwt) {
    return service.loadSample(userId(jwt));
  }

  /** JWT subject의 금융 프로필을 조회한다. */
  @GetMapping("/me/financial-profile")
  public FinancialProfileResponse financialProfile(@AuthenticationPrincipal Jwt jwt) {
    return service.financialProfile(userId(jwt));
  }

  /** JWT subject의 금융 프로필을 저장한다. */
  @PutMapping("/me/financial-profile")
  public FinancialProfileResponse upsertFinancialProfile(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FinancialProfileInput input) {
    return service.upsertFinancialProfile(userId(jwt), input);
  }

  /** 검증된 JWT subject를 사용자 ID로 변환한다. */
  private int userId(Jwt jwt) {
    return Integer.parseInt(jwt.getSubject());
  }
}

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

/** 검증된 JWT subject를 사용자 소유권 범위로 변환하는 계정·온보딩 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1")
public class UserController {
  private final UserService service;

  /**
   * 계정·온보딩 HTTP 요청을 유스케이스에 위임하도록 controller를 구성한다.
   *
   * @param service 계정·온보딩 유스케이스 서비스
   */
  public UserController(UserService service) {
    this.service = service;
  }

  /**
   * 현재 사용자의 소셜 표시 정보와 온보딩 누락 단계를 조회한다.
   *
   * @param jwt 검증된 access JWT
   * @return 현재 사용자와 온보딩 상태
   */
  @GetMapping("/me")
  public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
    return service.me(userId(jwt));
  }

  /**
   * 현재 사용자의 인적 프로필을 조회한다.
   *
   * @param jwt 검증된 access JWT
   * @return 저장된 프로필
   */
  @GetMapping("/me/profile")
  public ProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
    return service.profile(userId(jwt));
  }

  /**
   * 현재 사용자의 인적 프로필을 upsert한다.
   *
   * @param jwt 검증된 access JWT
   * @param input Bean Validation을 통과한 프로필 입력
   * @return 저장된 프로필
   */
  @PutMapping("/me/profile")
  public ProfileResponse upsertProfile(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileInput input) {
    return service.upsertProfile(userId(jwt), input);
  }

  /**
   * 현재 사용자의 빈 계정에 고정 샘플 데이터를 한 번만 적재한다.
   *
   * @param jwt 검증된 access JWT
   * @return 이번 호출의 적재 여부와 최초 적재 시각
   */
  @PostMapping("/me/sample-data")
  public SampleResponse loadSample(@AuthenticationPrincipal Jwt jwt) {
    return service.loadSample(userId(jwt));
  }

  /**
   * 현재 사용자의 금융 프로필을 조회한다.
   *
   * @param jwt 검증된 access JWT
   * @return 월소득·고정비·소비 하한 설정
   */
  @GetMapping("/me/financial-profile")
  public FinancialProfileResponse financialProfile(@AuthenticationPrincipal Jwt jwt) {
    return service.financialProfile(userId(jwt));
  }

  /**
   * 현재 사용자의 금융 프로필을 upsert한다.
   *
   * @param jwt 검증된 access JWT
   * @param input Bean Validation을 통과한 금융 프로필 입력
   * @return 저장값과 선택적 재계획 결과
   */
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

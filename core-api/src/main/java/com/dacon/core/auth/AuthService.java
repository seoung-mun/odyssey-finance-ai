package com.dacon.core.auth;

/** 검증된 외부 신원을 내부 사용자와 연결하고 자체 JWT 세션의 수명 주기를 관리한다. */
public interface AuthService {
  /**
   * 검증된 Google 신원으로 사용자를 조회하거나 생성하고 access·refresh 토큰을 발급한다.
   *
   * @param identity 서명과 필수 claim 검증을 마친 Google 신원
   * @return 발급 토큰과 신규 사용자 여부
   * @throws com.dacon.core.error.ApiException 가입 경합 뒤 기존 계정을 확인할 수 없는 경우
   */
  AuthResult login(GoogleIdentity identity);

  /**
   * refresh 토큰을 한 번만 소비하고 대체 세션과 새 access·refresh 토큰을 원자적으로 만든다.
   *
   * @param rawToken 브라우저 쿠키에서 받은 refresh JWT 원문
   * @return 새로 발급한 토큰 쌍
   * @throws com.dacon.core.error.ApiException 토큰이 유효하지 않거나 세션이 만료·폐기된 경우
   */
  AuthResult refresh(String rawToken);

  /**
   * 사용 가능한 refresh 세션을 폐기한다. 값이 없거나 이미 사용할 수 없는 세션이면 아무것도 변경하지 않는다.
   *
   * @param rawToken 브라우저 쿠키에서 받은 refresh JWT 원문; {@code null} 또는 공백 허용
   */
  void logout(String rawToken);
}

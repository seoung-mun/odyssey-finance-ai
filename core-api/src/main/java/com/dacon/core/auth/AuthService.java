package com.dacon.core.auth;

/** 로그인, refresh 회전과 로그아웃 유스케이스 계약이다. */
public interface AuthService {
  /** 검증된 Google identity로 로그인한다. */
  AuthResult login(GoogleIdentity identity);

  /** refresh token을 회전한다. */
  AuthResult refresh(String rawToken);

  /** refresh token을 폐기한다. */
  void logout(String rawToken);
}

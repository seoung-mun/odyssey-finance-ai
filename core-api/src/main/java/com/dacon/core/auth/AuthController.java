package com.dacon.core.auth;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Google OIDC 로그인을 자체 access 응답과 보안 refresh 쿠키로 변환하는 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
  private static final String COOKIE = "refresh_token";
  private static final String COOKIE_PATH = "/api/v1/auth";
  private final GoogleTokenVerifier google;
  private final AuthService auth;

  /**
   * @param google Google ID 토큰 검증기
   * @param auth 자체 세션을 생성·회전·폐기하는 인증 서비스
   */
  AuthController(GoogleTokenVerifier google, AuthService auth) {
    this.google = google;
    this.auth = auth;
  }

  /**
   * Google ID 토큰을 검증한 뒤 자체 access 토큰을 본문에, 7일 refresh 토큰을 HttpOnly 쿠키에 담는다.
   *
   * @param request Google ID 토큰 요청
   * @param response refresh 쿠키 헤더를 기록할 서블릿 응답
   * @return 15분 access 토큰과 신규 사용자 여부
   * @throws com.dacon.core.error.ApiException Google 검증 또는 내부 로그인에 실패한 경우
   */
  @PostMapping("/google")
  AuthTokens login(@Valid @RequestBody GoogleLoginRequest request, HttpServletResponse response) {
    AuthResult result = auth.login(google.verify(request.idToken()));
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(result.refreshToken(), Duration.ofDays(7)));
    return new AuthTokens(result.accessToken(), 900, result.isNewUser());
  }

  /**
   * refresh 쿠키를 일회성으로 회전하고 새 access 토큰과 refresh 쿠키를 반환한다.
   *
   * @param refreshToken 쿠키의 refresh JWT 원문
   * @param response 회전된 refresh 쿠키를 기록할 서블릿 응답
   * @return 새 15분 access 토큰
   * @throws com.dacon.core.error.ApiException 쿠키 또는 DB 세션이 유효하지 않은 경우
   */
  @PostMapping("/refresh")
  AuthTokens refresh(
      @CookieValue(name = COOKIE, required = false) String refreshToken,
      HttpServletResponse response) {
    AuthResult result = auth.refresh(refreshToken);
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(result.refreshToken(), Duration.ofDays(7)));
    return new AuthTokens(result.accessToken(), 900, false);
  }

  /**
   * 전달된 refresh 세션을 폐기하고 브라우저 쿠키를 즉시 만료시킨다.
   *
   * @param refreshToken 쿠키의 refresh JWT 원문; 없어도 로그아웃은 성공한다
   * @return 본문 없는 204 응답과 만료 쿠키
   */
  @PostMapping("/logout")
  ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String refreshToken) {
    auth.logout(refreshToken);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO))
        .build();
  }

  /**
   * HttpOnly·Secure·SameSite=Strict 속성과 인증 경로가 고정된 refresh 쿠키를 직렬화한다.
   *
   * @param token 쿠키 값
   * @param maxAge 쿠키 수명; 0이면 삭제 쿠키
   * @return {@code Set-Cookie} 헤더 값
   */
  private String cookie(String token, Duration maxAge) {
    return ResponseCookie.from(COOKIE, token)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path(COOKIE_PATH)
        .maxAge(maxAge)
        .build()
        .toString();
  }

  /**
   * 브라우저가 Google 로그인 뒤 전달하는 교환 요청이다.
   *
   * @param idToken Google이 발급한 비어 있지 않은 OIDC ID 토큰
   */
  record GoogleLoginRequest(@NotBlank String idToken) {}

  /**
   * 인증 성공 응답이다. refresh 토큰은 이 본문이 아니라 HttpOnly 쿠키로만 전달된다.
   *
   * @param accessToken 자체 access JWT
   * @param expiresIn access JWT 만료까지의 초
   * @param isNewUser 새 내부 계정을 만들었으면 {@code true}
   */
  record AuthTokens(String accessToken, int expiresIn, boolean isNewUser) {}
}

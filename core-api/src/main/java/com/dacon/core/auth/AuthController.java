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

/** Google 로그인, token 회전과 로그아웃 HTTP 경계를 제공한다. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
  private static final String COOKIE = "refresh_token";
  private static final String COOKIE_PATH = "/api/v1/auth";
  private final GoogleTokenVerifier google;
  private final AuthService auth;

  /** token 검증기와 인증 서비스로 controller를 만든다. */
  AuthController(GoogleTokenVerifier google, AuthService auth) {
    this.google = google;
    this.auth = auth;
  }

  /** Google ID token을 자체 token으로 교환하고 refresh cookie를 설정한다. */
  @PostMapping("/google")
  AuthTokens login(@Valid @RequestBody GoogleLoginRequest request, HttpServletResponse response) {
    AuthResult result = auth.login(google.verify(request.idToken()));
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(result.refreshToken(), Duration.ofDays(7)));
    return new AuthTokens(result.accessToken(), 900, result.isNewUser());
  }

  /** refresh cookie를 회전하고 새 access token과 cookie를 반환한다. */
  @PostMapping("/refresh")
  AuthTokens refresh(
      @CookieValue(name = COOKIE, required = false) String refreshToken,
      HttpServletResponse response) {
    AuthResult result = auth.refresh(refreshToken);
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(result.refreshToken(), Duration.ofDays(7)));
    return new AuthTokens(result.accessToken(), 900, false);
  }

  /** 전달된 refresh session을 폐기하고 cookie를 만료시킨다. */
  @PostMapping("/logout")
  ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String refreshToken) {
    auth.logout(refreshToken);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO))
        .build();
  }

  /** 보안 속성과 endpoint path가 고정된 refresh cookie 문자열을 만든다. */
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

  /** 브라우저가 전달하는 Google ID token 요청이다. */
  record GoogleLoginRequest(@NotBlank String idToken) {}

  /** access token과 만료 초, 신규 사용자 여부를 반환한다. */
  record AuthTokens(String accessToken, int expiresIn, boolean isNewUser) {}
}

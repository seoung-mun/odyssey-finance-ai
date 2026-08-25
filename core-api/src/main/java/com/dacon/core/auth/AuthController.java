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

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
  private static final String COOKIE = "refresh_token";
  private static final String COOKIE_PATH = "/api/v1/auth/refresh";
  private final GoogleTokenVerifier google;
  private final AuthService auth;

  AuthController(GoogleTokenVerifier google, AuthService auth) {
    this.google = google;
    this.auth = auth;
  }

  @PostMapping("/google")
  AuthTokens login(@Valid @RequestBody GoogleLoginRequest request, HttpServletResponse response) {
    var result = auth.login(google.verify(request.idToken()));
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(result.refreshToken(), Duration.ofDays(7)));
    return new AuthTokens(result.accessToken(), 900, result.isNewUser());
  }

  @PostMapping("/refresh")
  AuthTokens refresh(
      @CookieValue(name = COOKIE, required = false) String refreshToken,
      HttpServletResponse response) {
    var result = auth.refresh(refreshToken);
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(result.refreshToken(), Duration.ofDays(7)));
    return new AuthTokens(result.accessToken(), 900, false);
  }

  @PostMapping("/logout")
  ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String refreshToken) {
    auth.logout(refreshToken);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO))
        .build();
  }

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

  record GoogleLoginRequest(@NotBlank String idToken) {}

  record AuthTokens(String accessToken, int expiresIn, boolean isNewUser) {}
}

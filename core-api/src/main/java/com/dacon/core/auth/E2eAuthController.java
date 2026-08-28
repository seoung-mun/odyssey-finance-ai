package com.dacon.core.auth;

import com.dacon.core.error.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 로컬 통합 테스트에서만 실제 사용자·JWT 세션을 만드는 인증 경계다. */
@Profile("e2e")
@RestController
public class E2eAuthController {
  private final AuthService auth;
  private final byte[] expectedToken;

  E2eAuthController(AuthService auth, @Value("${app.e2e-token:}") String expectedToken) {
    this.auth = auth;
    this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
  }

  @PostMapping("/api/v1/auth/e2e")
  AuthTokens login(
      @RequestHeader("X-E2E-Token") String token,
      @RequestParam @NotBlank String username,
      HttpServletResponse response) {
    if (expectedToken.length == 0
        || !MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8))) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_E2E_TOKEN", "E2E 인증 토큰을 확인해 주세요.");
    }
    AuthResult result =
        auth.login(new GoogleIdentity("e2e:" + username, username + "@e2e.local", username, null));
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("refresh_token", result.refreshToken())
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/api/v1/auth")
            .maxAge(Duration.ofDays(7))
            .build()
            .toString());
    return new AuthTokens(result.accessToken(), 900, result.isNewUser());
  }

  record AuthTokens(String accessToken, int expiresIn, boolean isNewUser) {}
}

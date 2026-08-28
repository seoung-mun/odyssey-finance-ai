package com.dacon.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthControllerTest {
  @Test
  void logoutDeletesRefreshCookieAtSharedAuthScope() {
    AuthService auth = mock(AuthService.class);
    AuthController controller = new AuthController(mock(GoogleTokenVerifier.class), auth);

    ResponseEntity<Void> response = controller.logout("refresh");

    assertThat(response.getHeaders().getFirst("Set-Cookie"))
        .contains("refresh_token=")
        .contains("Path=/api/v1/auth")
        .contains("Max-Age=0")
        .contains("Secure")
        .contains("HttpOnly")
        .contains("SameSite=Strict");
  }

  @Test
  void refreshCookieIsAvailableToLogoutEndpoint() {
    GoogleTokenVerifier google = mock(GoogleTokenVerifier.class);
    AuthService auth = mock(AuthService.class);
    GoogleIdentity identity = new GoogleIdentity("subject", "user@example.com", null, null);
    when(google.verify("google-token")).thenReturn(identity);
    when(auth.login(identity)).thenReturn(new AuthResult("access", "refresh", false));
    AuthController controller = new AuthController(google, auth);
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.login(new AuthController.GoogleLoginRequest("google-token"), response);

    assertThat(response.getHeader("Set-Cookie"))
        .contains("Path=/api/v1/auth;")
        .doesNotContain("Path=/api/v1/auth/refresh");
  }
}

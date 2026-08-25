package com.dacon.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthControllerTest {
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

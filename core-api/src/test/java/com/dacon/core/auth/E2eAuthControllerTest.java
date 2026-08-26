package com.dacon.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.web.MockHttpServletResponse;

class E2eAuthControllerTest {
  @Test
  void controllerExistsOnlyInE2eProfileAndUsesNormalLoginPath() {
    AuthService auth = mock(AuthService.class);
    when(auth.login(new GoogleIdentity("e2e:alice", "alice@e2e.local", "alice", null)))
        .thenReturn(new AuthResult("access", "refresh", true));
    E2eAuthController controller = new E2eAuthController(auth, "secret");
    MockHttpServletResponse response = new MockHttpServletResponse();

    E2eAuthController.AuthTokens result = controller.login("secret", "alice", response);

    assertThat(E2eAuthController.class.getAnnotation(Profile.class).value()).containsExactly("e2e");
    assertThat(result.accessToken()).isEqualTo("access");
    assertThat(response.getHeader("Set-Cookie"))
        .contains("refresh_token=refresh")
        .contains("Path=/api/v1/auth")
        .contains("Secure")
        .contains("HttpOnly")
        .contains("SameSite=Strict")
        .doesNotContain("Path=/api/v1/auth/refresh");
    verify(auth).login(new GoogleIdentity("e2e:alice", "alice@e2e.local", "alice", null));
  }
}

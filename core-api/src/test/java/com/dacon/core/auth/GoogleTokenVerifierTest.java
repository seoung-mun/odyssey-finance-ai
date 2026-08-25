package com.dacon.core.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dacon.core.error.ApiException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class GoogleTokenVerifierTest {
  @Test
  void springSelectsProductionConstructorWithoutGoogleDiscovery() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("test", Map.of("app.google-client-id", "client-id")));
      context.register(GoogleTokenVerifier.class);

      context.refresh();

      org.assertj.core.api.Assertions.assertThat(context.getBean(GoogleTokenVerifier.class))
          .isNotNull();
    }
  }

  @Test
  void discoveryFailureIsServiceUnavailable() {
    GoogleTokenVerifier verifier =
        new GoogleTokenVerifier(
            "client-id",
            () -> {
              throw new IllegalStateException("network down");
            });

    assertThatThrownBy(() -> verifier.verify("token"))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("GOOGLE_AUTH_UNAVAILABLE");
  }

  @Test
  void invalidJwtIsUnauthorized() {
    org.springframework.security.oauth2.jwt.NimbusJwtDecoder decoder =
        mock(org.springframework.security.oauth2.jwt.NimbusJwtDecoder.class);
    when(decoder.decode("bad"))
        .thenThrow(new org.springframework.security.oauth2.jwt.JwtException("bad token"));
    GoogleTokenVerifier verifier = new GoogleTokenVerifier("client-id", () -> decoder);

    assertThatThrownBy(() -> verifier.verify("bad"))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("INVALID_GOOGLE_TOKEN");
  }
}

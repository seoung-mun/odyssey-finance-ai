package com.dacon.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class SecurityConfigTest {
  private final SecurityConfig config = new SecurityConfig();

  @Test
  void allowsOnlyConfiguredCredentialedOrigin() {
    CorsConfigurationSource source = config.corsConfigurationSource("https://app.example.com");
    MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/auth/refresh");

    CorsConfiguration cors = source.getCorsConfiguration(request);

    assertThat(cors).isNotNull();
    assertThat(cors.getAllowedOrigins()).containsExactly("https://app.example.com");
    assertThat(cors.getAllowCredentials()).isTrue();
    assertThat(cors.getAllowedOrigins()).doesNotContain("*");
  }

  @Test
  void deniesCrossOriginWhenProductionOriginIsUnset() {
    CorsConfigurationSource source = config.corsConfigurationSource(" ");
    MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/goals");

    CorsConfiguration cors = source.getCorsConfiguration(request);

    assertThat(cors).isNotNull();
    assertThat(cors.getAllowedOrigins()).isEmpty();
  }
}

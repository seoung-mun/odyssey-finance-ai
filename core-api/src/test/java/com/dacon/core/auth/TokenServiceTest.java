package com.dacon.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class TokenServiceTest {
  private static final String SECRET = "01234567890123456789012345678901";

  @Test
  void springSelectsProductionConstructor() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("test", java.util.Map.of("app.jwt-secret", SECRET)));
      context.register(TokenService.class);

      context.refresh();

      assertThat(context.getBean(TokenService.class)).isNotNull();
    }
  }

  @Test
  void accessAndRefreshHaveFixedLifetimesAndKinds() {
    Instant now = Instant.parse("2026-08-25T00:00:00Z");
    TokenService service = new TokenService(SECRET, Clock.fixed(now, ZoneOffset.UTC));

    String access = service.issueAccess(3);
    String refresh = service.issueRefresh(3);

    assertThat(service.decodeAccess(access).getExpiresAt()).isEqualTo(now.plusSeconds(900));
    assertThat(service.decodeRefresh(refresh).getExpiresAt()).isEqualTo(now.plusSeconds(604_800));
    assertThat(service.decodeRefresh(refresh).getClaimAsString("kind")).isEqualTo("refresh");
    assertThat(service.decodeAccess(access).getAudience()).containsExactly("odyssey-web");
    assertThat(service.decodeAccess(access).getSubject()).isEqualTo("3");
  }

  @Test
  void invalidJwtIsRejected() {
    TokenService service = new TokenService(SECRET, Clock.systemUTC());

    assertThatThrownBy(() -> service.decodeAccess("broken"))
        .isInstanceOf(com.dacon.core.error.ApiException.class)
        .extracting("code")
        .isEqualTo("INVALID_TOKEN");
  }

  @Test
  void wrongAudienceAndNonNumericSubjectAreRejected() {
    TokenService service = new TokenService(SECRET, Clock.systemUTC());

    assertThatThrownBy(() -> service.decodeAccess(signed("abc", "odyssey-web")))
        .isInstanceOf(com.dacon.core.error.ApiException.class);
    assertThatThrownBy(() -> service.decodeAccess(signed("3", "other-client")))
        .isInstanceOf(com.dacon.core.error.ApiException.class);
  }

  private String signed(String subject, String audience) {
    SecretKeySpec key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(TokenService.ISSUER)
            .subject(subject)
            .audience(List.of(audience))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(60))
            .claim("kind", "access")
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }
}

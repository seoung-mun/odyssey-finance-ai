package com.dacon.core.auth;

import com.dacon.core.error.ApiException;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** 자체 access·refresh JWT를 표준 JOSE 구현으로 발급·검증한다. */
@Component
public class TokenService {
  static final String ISSUER = "odyssey-core";
  private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
  private static final Duration REFRESH_TTL = Duration.ofDays(7);

  private final Clock clock;
  private final JwtEncoder encoder;
  private final NimbusJwtDecoder decoder;

  /** 환경 서명키와 시스템 UTC clock으로 token service를 만든다. */
  @Autowired
  public TokenService(@Value("${app.jwt-secret}") String secret) {
    this(secret, Clock.systemUTC());
  }

  /** 테스트 가능한 clock과 서명키로 token service를 만든다. */
  TokenService(String secret, Clock clock) {
    if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalArgumentException("JWT_SECRET must be at least 32 bytes");
    }
    this.clock = clock;
    SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
    timestampValidator.setClock(clock);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(timestampValidator, new JwtIssuerValidator(ISSUER)));
  }

  /** 사용자의 15분 access token을 발급한다. */
  public String issueAccess(int userId) {
    return issue(userId, "access", ACCESS_TTL);
  }

  /** 사용자의 7일 refresh token을 발급한다. */
  public String issueRefresh(int userId) {
    return issue(userId, "refresh", REFRESH_TTL);
  }

  /** access 전용 claim과 서명을 검증해 JWT를 반환한다. */
  public Jwt decodeAccess(String token) {
    return decode(token, "access");
  }

  /** refresh 전용 claim과 서명을 검증해 JWT를 반환한다. */
  public Jwt decodeRefresh(String token) {
    return decode(token, "refresh");
  }

  /** Resource Server가 사용할 access 전용 decoder를 반환한다. */
  public JwtDecoder accessDecoder() {
    return this::decodeAccess;
  }

  /** refresh 원문을 저장하지 않도록 SHA-256 hex digest를 반환한다. */
  public String digest(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  /** token 종류와 TTL을 반영한 서명 JWT를 발급한다. */
  private String issue(int userId, String kind, Duration ttl) {
    if (userId <= 0) {
      throw new IllegalArgumentException("userId must be positive");
    }
    Instant issuedAt = clock.instant();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .audience(List.of("odyssey-web"))
            .subject(Integer.toString(userId))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plus(ttl))
            .id(UUID.randomUUID().toString())
            .claim("kind", kind)
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }

  /** 공통 서명·issuer·audience·subject·종류를 검증한다. */
  private Jwt decode(String token, String expectedKind) {
    try {
      Jwt jwt = decoder.decode(token);
      if (!expectedKind.equals(jwt.getClaimAsString("kind"))
          || !jwt.getAudience().contains("odyssey-web")
          || jwt.getSubject() == null
          || !jwt.getSubject().matches("^[1-9][0-9]*$")) {
        throw unauthorized();
      }
      return jwt;
    } catch (RuntimeException exception) {
      if (exception instanceof ApiException apiException) {
        throw apiException;
      }
      throw unauthorized();
    }
  }

  /** token 검증 실패를 401 API 예외로 만든다. */
  private ApiException unauthorized() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "인증이 만료되었습니다.");
  }
}

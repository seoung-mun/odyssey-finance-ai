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

/** HS256 자체 JWT를 발급·검증하고 refresh 원문의 저장용 digest를 계산한다. */
@Component
public class TokenService {
  static final String ISSUER = "odyssey-core";
  private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
  private static final Duration REFRESH_TTL = Duration.ofDays(7);

  private final Clock clock;
  private final JwtEncoder encoder;
  private final NimbusJwtDecoder decoder;

  /**
   * 환경 서명키와 시스템 UTC 시계로 서비스를 구성한다.
   *
   * @param secret HS256 서명 비밀값
   * @throws IllegalArgumentException 비밀값이 UTF-8 기준 32바이트보다 짧은 경우
   */
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

  /**
   * 지정 사용자를 subject로 하는 15분 access JWT를 발급한다.
   *
   * @param userId 양의 내부 사용자 ID
   * @return 서명된 JWT 원문
   * @throws IllegalArgumentException 사용자 ID가 양수가 아닌 경우
   */
  public String issueAccess(int userId) {
    return issue(userId, "access", ACCESS_TTL);
  }

  /**
   * 지정 사용자를 subject로 하는 7일 refresh JWT를 발급한다.
   *
   * @param userId 양의 내부 사용자 ID
   * @return 서명된 JWT 원문
   * @throws IllegalArgumentException 사용자 ID가 양수가 아닌 경우
   */
  public String issueRefresh(int userId) {
    return issue(userId, "refresh", REFRESH_TTL);
  }

  /**
   * access 전용 kind, 서명, 만료, issuer, audience와 양의 사용자 subject를 검증한다.
   *
   * @param token 검증할 JWT 원문
   * @return 검증을 마친 access JWT
   * @throws ApiException 어느 검증이든 실패한 경우 {@code INVALID_TOKEN} 401
   */
  public Jwt decodeAccess(String token) {
    return decode(token, "access");
  }

  /**
   * refresh 전용 kind, 서명, 만료, issuer, audience와 양의 사용자 subject를 검증한다.
   *
   * @param token 검증할 JWT 원문
   * @return 검증을 마친 refresh JWT
   * @throws ApiException 어느 검증이든 실패한 경우 {@code INVALID_TOKEN} 401
   */
  public Jwt decodeRefresh(String token) {
    return decode(token, "refresh");
  }

  /**
   * Spring Security Resource Server가 access 검증에 사용할 decoder를 반환한다.
   *
   * @return {@link #decodeAccess(String)}에 위임하는 decoder
   */
  public JwtDecoder accessDecoder() {
    return this::decodeAccess;
  }

  /**
   * refresh 토큰 원문을 DB에 저장하지 않도록 SHA-256 16진 digest를 계산한다.
   *
   * @param token digest할 토큰 원문
   * @return 소문자 16진 SHA-256 digest
   * @throws IllegalStateException JVM이 SHA-256을 제공하지 않는 경우
   */
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

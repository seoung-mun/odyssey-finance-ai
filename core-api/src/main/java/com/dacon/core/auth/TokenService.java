package com.dacon.core.auth;

import com.dacon.core.error.ApiException;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
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

@Component
public class TokenService {
  static final String ISSUER = "odyssey-core";
  private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
  private static final Duration REFRESH_TTL = Duration.ofDays(7);

  private final Clock clock;
  private final JwtEncoder encoder;
  private final NimbusJwtDecoder decoder;

  public TokenService(@Value("${app.jwt-secret}") String secret) {
    this(secret, Clock.systemUTC());
  }

  TokenService(String secret, Clock clock) {
    if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalArgumentException("JWT_SECRET must be at least 32 bytes");
    }
    this.clock = clock;
    var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    var timestampValidator = new JwtTimestampValidator();
    timestampValidator.setClock(clock);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(timestampValidator, new JwtIssuerValidator(ISSUER)));
  }

  public String issueAccess(int userId) {
    return issue(userId, "access", ACCESS_TTL);
  }

  public String issueRefresh(int userId) {
    return issue(userId, "refresh", REFRESH_TTL);
  }

  public Jwt decodeAccess(String token) {
    return decode(token, "access");
  }

  public Jwt decodeRefresh(String token) {
    return decode(token, "refresh");
  }

  public JwtDecoder accessDecoder() {
    return this::decodeAccess;
  }

  public String digest(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private String issue(int userId, String kind, Duration ttl) {
    Instant issuedAt = clock.instant();
    var claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
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

  private Jwt decode(String token, String expectedKind) {
    try {
      var jwt = decoder.decode(token);
      if (!expectedKind.equals(jwt.getClaimAsString("kind"))) {
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

  private ApiException unauthorized() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "인증이 만료되었습니다.");
  }
}

package com.dacon.core.auth;

import com.dacon.core.error.ApiException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/** Google OIDC token의 서명·issuer·audience·필수 claim을 검증한다. */
@Component
class GoogleTokenVerifier {
  private static final String ISSUER = "https://accounts.google.com";
  private final String clientId;
  private final Supplier<NimbusJwtDecoder> decoderFactory;
  private volatile NimbusJwtDecoder decoder;

  /** client ID와 운영 decoder factory로 verifier를 만든다. */
  @Autowired
  GoogleTokenVerifier(@Value("${app.google-client-id}") String clientId) {
    this(clientId, GoogleTokenVerifier::createDecoder);
  }

  /** 테스트 또는 운영에서 지정한 decoder factory로 verifier를 만든다. */
  GoogleTokenVerifier(String clientId, Supplier<NimbusJwtDecoder> decoderFactory) {
    this.clientId = clientId;
    this.decoderFactory = decoderFactory;
  }

  /** token을 검증해 내부에서 사용할 Google identity를 반환한다. */
  GoogleIdentity verify(String token) {
    if (clientId.isBlank()) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_AUTH_NOT_CONFIGURED", "Google 로그인 설정이 필요합니다.");
    }
    NimbusJwtDecoder googleDecoder;
    try {
      googleDecoder = decoder();
    } catch (RuntimeException exception) {
      throw unavailable();
    }
    Jwt jwt;
    try {
      jwt = googleDecoder.decode(token);
    } catch (org.springframework.security.oauth2.jwt.JwtException exception) {
      if (hasNetworkCause(exception)) {
        throw unavailable();
      }
      throw invalid();
    } catch (RuntimeException exception) {
      throw unavailable();
    }
    try {
      if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
        throw invalid();
      }
      if (jwt.getSubject() == null
          || jwt.getSubject().isBlank()
          || jwt.getClaimAsString("email") == null
          || jwt.getClaimAsString("email").isBlank()) {
        throw invalid();
      }
      return new GoogleIdentity(
          jwt.getSubject(),
          jwt.getClaimAsString("email"),
          jwt.getClaimAsString("name"),
          jwt.getClaimAsString("picture"));
    } catch (ApiException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  /** 예외 원인 체인에 네트워크 입출력 장애가 있는지 확인한다. */
  private boolean hasNetworkCause(Throwable exception) {
    Throwable current = exception;
    while (current != null) {
      if (current instanceof java.io.IOException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  /** Google issuer metadata를 이용해 decoder를 만든다. */
  private static NimbusJwtDecoder createDecoder() {
    return (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(ISSUER);
  }

  /** Google discovery·JWKS 장애를 503 예외로 만든다. */
  private ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "GOOGLE_AUTH_UNAVAILABLE",
        "Google 인증 서비스에 일시적으로 연결할 수 없습니다.");
  }

  /** decoder를 지연 생성하고 audience validator를 설정해 재사용한다. */
  private NimbusJwtDecoder decoder() {
    NimbusJwtDecoder current = decoder;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (decoder == null) {
        decoder = decoderFactory.get();
        OAuth2TokenValidator<Jwt> audience =
            jwt ->
                jwt.getAudience().contains(clientId)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "invalid audience", null));
        decoder.setJwtValidator(
            new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), audience));
      }
      return decoder;
    }
  }

  /** 유효하지 않은 Google token을 401 예외로 만든다. */
  private ApiException invalid() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN", "Google 인증을 확인할 수 없습니다.");
  }
}

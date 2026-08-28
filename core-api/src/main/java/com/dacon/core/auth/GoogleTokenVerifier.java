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

/** Google OIDC 토큰의 서명·issuer·audience·이메일 검증 여부와 필수 claim을 검증한다. */
@Component
class GoogleTokenVerifier {
  private static final String ISSUER = "https://accounts.google.com";
  private final String clientId;
  private final Supplier<NimbusJwtDecoder> decoderFactory;
  private volatile NimbusJwtDecoder decoder;

  /**
   * 운영 Google metadata 기반 decoder를 지연 생성하도록 verifier를 구성한다.
   *
   * @param clientId audience claim과 비교할 OAuth client ID
   */
  @Autowired
  GoogleTokenVerifier(@Value("${app.google-client-id}") String clientId) {
    this(clientId, GoogleTokenVerifier::createDecoder);
  }

  /**
   * 지정한 decoder factory로 verifier를 구성한다.
   *
   * @param clientId audience claim과 비교할 OAuth client ID
   * @param decoderFactory 최초 검증 시 decoder를 만들 factory
   */
  GoogleTokenVerifier(String clientId, Supplier<NimbusJwtDecoder> decoderFactory) {
    this.clientId = clientId;
    this.decoderFactory = decoderFactory;
  }

  /**
   * 토큰을 검증해 인증 서비스가 신뢰할 수 있는 최소 Google 신원으로 변환한다.
   *
   * @param token Google OIDC ID 토큰 원문
   * @return subject와 검증된 이메일을 포함한 신원
   * @throws ApiException 설정·Google 통신 장애면 503, 토큰 또는 claim이 유효하지 않으면 401
   */
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

  /**
   * decoder 실패의 원인 체인에서 인증 실패와 외부 네트워크 장애를 구분한다.
   *
   * @param exception 검사할 최상위 예외
   * @return 원인 중 {@link java.io.IOException}이 있으면 {@code true}
   */
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

  /**
   * Google issuer metadata와 JWKS를 이용해 decoder를 만든다.
   *
   * @return Google issuer 기반 Nimbus decoder
   */
  private static NimbusJwtDecoder createDecoder() {
    return (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(ISSUER);
  }

  /**
   * @return Google discovery·JWKS 장애를 나타내는 503 예외
   */
  private ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "GOOGLE_AUTH_UNAVAILABLE",
        "Google 인증 서비스에 일시적으로 연결할 수 없습니다.");
  }

  /**
   * Google metadata/JWKS 기반 decoder를 최초 요청에서 한 번 생성하고 audience 검증기를 결합한다.
   *
   * @return 이후 호출에서도 재사용할 thread-safe decoder
   */
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

  /**
   * @return 유효하지 않은 Google 토큰 또는 claim을 나타내는 401 예외
   */
  private ApiException invalid() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN", "Google 인증을 확인할 수 없습니다.");
  }
}

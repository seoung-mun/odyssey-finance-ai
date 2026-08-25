package com.dacon.core.auth;

import com.dacon.core.error.ApiException;
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

@Component
class GoogleTokenVerifier {
  private static final String ISSUER = "https://accounts.google.com";
  private final String clientId;
  private volatile NimbusJwtDecoder decoder;

  GoogleTokenVerifier(@Value("${app.google-client-id}") String clientId) {
    this.clientId = clientId;
  }

  GoogleIdentity verify(String token) {
    if (clientId.isBlank()) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_AUTH_NOT_CONFIGURED", "Google 로그인 설정이 필요합니다.");
    }
    try {
      var jwt = decoder().decode(token);
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
    } catch (RuntimeException exception) {
      if (exception instanceof ApiException apiException) {
        throw apiException;
      }
      throw invalid();
    }
  }

  private NimbusJwtDecoder decoder() {
    var current = decoder;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (decoder == null) {
        decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(ISSUER);
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

  private ApiException invalid() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN", "Google 인증을 확인할 수 없습니다.");
  }
}

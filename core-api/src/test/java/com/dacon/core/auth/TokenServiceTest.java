package com.dacon.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TokenServiceTest {
  @Test
  void accessAndRefreshHaveFixedLifetimesAndKinds() {
    var now = Instant.parse("2026-08-25T00:00:00Z");
    var service =
        new TokenService("01234567890123456789012345678901", Clock.fixed(now, ZoneOffset.UTC));

    var access = service.issueAccess(3);
    var refresh = service.issueRefresh(3);

    assertThat(service.decodeAccess(access).getExpiresAt()).isEqualTo(now.plusSeconds(900));
    assertThat(service.decodeRefresh(refresh).getExpiresAt()).isEqualTo(now.plusSeconds(604_800));
    assertThat(service.decodeRefresh(refresh).getClaimAsString("kind")).isEqualTo("refresh");
  }

  @Test
  void invalidJwtIsRejected() {
    var service = new TokenService("01234567890123456789012345678901", Clock.systemUTC());

    assertThatThrownBy(() -> service.decodeAccess("broken"))
        .isInstanceOf(com.dacon.core.error.ApiException.class)
        .extracting("code")
        .isEqualTo("INVALID_TOKEN");
  }
}

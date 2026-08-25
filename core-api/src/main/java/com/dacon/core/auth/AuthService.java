package com.dacon.core.auth;

import com.dacon.core.error.ApiException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuthService {
  private final UserAccountRepository users;
  private final SocialAccountRepository socialAccounts;
  private final RefreshSessionRepository refreshSessions;
  private final TokenService tokens;

  AuthService(
      UserAccountRepository users,
      SocialAccountRepository socialAccounts,
      RefreshSessionRepository refreshSessions,
      TokenService tokens) {
    this.users = users;
    this.socialAccounts = socialAccounts;
    this.refreshSessions = refreshSessions;
    this.tokens = tokens;
  }

  @Transactional
  AuthResult login(GoogleIdentity identity) {
    var existing = socialAccounts.findByProviderAndProviderSubject("GOOGLE", identity.subject());
    boolean isNew = existing.isEmpty();
    var social =
        existing.orElseGet(
            () -> {
              var user = users.save(new UserAccount());
              return new SocialAccount(user.id(), identity);
            });
    social.update(identity);
    socialAccounts.save(social);
    return issue(social.userId(), isNew);
  }

  @Transactional
  AuthResult refresh(String rawToken) {
    var jwt = tokens.decodeRefresh(rawToken);
    var old =
        refreshSessions
            .findByDigestForUpdate(tokens.digest(rawToken))
            .filter(session -> session.usableAt(Instant.now()))
            .orElseThrow(this::invalidRefresh);
    if (!Integer.toString(old.userId()).equals(jwt.getSubject())) {
      throw invalidRefresh();
    }

    var newRaw = tokens.issueRefresh(old.userId());
    var replacement =
        refreshSessions.saveAndFlush(
            new RefreshSession(
                old.userId(), tokens.digest(newRaw), tokens.decodeRefresh(newRaw).getExpiresAt()));
    old.replaceWith(replacement.id(), Instant.now());
    refreshSessions.save(old);
    return new AuthResult(tokens.issueAccess(old.userId()), newRaw, false);
  }

  @Transactional
  void logout(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      return;
    }
    refreshSessions
        .findByDigestForUpdate(tokens.digest(rawToken))
        .filter(session -> session.usableAt(Instant.now()))
        .ifPresent(
            session -> {
              session.revoke(Instant.now());
              refreshSessions.save(session);
            });
  }

  private AuthResult issue(int userId, boolean isNew) {
    var refresh = tokens.issueRefresh(userId);
    refreshSessions.save(
        new RefreshSession(
            userId, tokens.digest(refresh), tokens.decodeRefresh(refresh).getExpiresAt()));
    return new AuthResult(tokens.issueAccess(userId), refresh, isNew);
  }

  private ApiException invalidRefresh() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "다시 로그인해 주세요.");
  }
}

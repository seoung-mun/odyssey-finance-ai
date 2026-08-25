package com.dacon.core.auth;

import com.dacon.core.error.ApiException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** 소셜 로그인 계정 생성과 refresh session 회전·폐기를 구현한다. */
@Service
public class AuthServiceImpl implements AuthService {
  private final UserAccountRepository users;
  private final SocialAccountRepository socialAccounts;
  private final RefreshSessionRepository refreshSessions;
  private final TokenService tokens;
  private final TransactionOperations transactions;

  /** 저장소와 Spring transaction manager로 인증 서비스를 만든다. */
  @Autowired
  public AuthServiceImpl(
      UserAccountRepository users,
      SocialAccountRepository socialAccounts,
      RefreshSessionRepository refreshSessions,
      TokenService tokens,
      PlatformTransactionManager transactionManager) {
    this(
        users,
        socialAccounts,
        refreshSessions,
        tokens,
        new TransactionTemplate(transactionManager));
  }

  /** 저장소와 명시적 transaction runner로 인증 서비스를 만든다. */
  AuthServiceImpl(
      UserAccountRepository users,
      SocialAccountRepository socialAccounts,
      RefreshSessionRepository refreshSessions,
      TokenService tokens,
      TransactionOperations transactions) {
    this.users = users;
    this.socialAccounts = socialAccounts;
    this.refreshSessions = refreshSessions;
    this.tokens = tokens;
    this.transactions = transactions;
  }

  /** 검증된 Google identity를 로그인시키며 최초 가입 경합은 새 트랜잭션에서 재조회한다. */
  @Override
  public AuthResult login(GoogleIdentity identity) {
    try {
      return transactions.execute(status -> loginInTransaction(identity));
    } catch (DataIntegrityViolationException exception) {
      return transactions.execute(status -> loginExistingInTransaction(identity));
    }
  }

  /** 한 트랜잭션에서 계정 조회·생성, 로그인 정보와 refresh session을 저장한다. */
  private AuthResult loginInTransaction(GoogleIdentity identity) {
    Optional<SocialAccount> existing =
        socialAccounts.findByProviderAndProviderSubject("GOOGLE", identity.subject());
    boolean newUser = existing.isEmpty();
    SocialAccount social =
        existing.orElseGet(
            () -> {
              UserAccount user = users.save(new UserAccount());
              return new SocialAccount(user.id(), identity);
            });
    social.update(identity);
    socialAccounts.save(social);
    return issue(social.userId(), newUser);
  }

  /** unique 경합에서 승리한 기존 계정을 새 트랜잭션으로 재조회해 로그인한다. */
  private AuthResult loginExistingInTransaction(GoogleIdentity identity) {
    SocialAccount social =
        socialAccounts
            .findByProviderAndProviderSubject("GOOGLE", identity.subject())
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.CONFLICT, "LOGIN_CONFLICT", "계정 상태를 다시 확인해 주세요."));
    social.update(identity);
    socialAccounts.save(social);
    return issue(social.userId(), false);
  }

  /** refresh session을 잠그고 기존 token 폐기와 새 token 발급을 원자 처리한다. */
  @Transactional
  @Override
  public AuthResult refresh(String rawToken) {
    org.springframework.security.oauth2.jwt.Jwt jwt = tokens.decodeRefresh(rawToken);
    RefreshSession old =
        refreshSessions
            .findByDigestForUpdate(tokens.digest(rawToken))
            .filter(session -> session.usableAt(Instant.now()))
            .orElseThrow(this::invalidRefresh);
    if (!Integer.toString(old.userId()).equals(jwt.getSubject())) {
      throw invalidRefresh();
    }

    String newRaw = tokens.issueRefresh(old.userId());
    RefreshSession replacement =
        refreshSessions.saveAndFlush(
            new RefreshSession(
                old.userId(), tokens.digest(newRaw), tokens.decodeRefresh(newRaw).getExpiresAt()));
    old.replaceWith(replacement.id(), Instant.now());
    refreshSessions.save(old);
    return new AuthResult(tokens.issueAccess(old.userId()), newRaw, false);
  }

  /** 전달된 refresh session이 사용 가능하면 폐기하며 token이 없으면 종료한다. */
  @Transactional
  @Override
  public void logout(String rawToken) {
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

  /** access·refresh token을 발급하고 refresh digest만 DB에 저장한다. */
  private AuthResult issue(int userId, boolean isNew) {
    String refresh = tokens.issueRefresh(userId);
    refreshSessions.save(
        new RefreshSession(
            userId, tokens.digest(refresh), tokens.decodeRefresh(refresh).getExpiresAt()));
    return new AuthResult(tokens.issueAccess(userId), refresh, isNew);
  }

  /** 사용할 수 없는 refresh token을 401 예외로 만든다. */
  private ApiException invalidRefresh() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "다시 로그인해 주세요.");
  }
}

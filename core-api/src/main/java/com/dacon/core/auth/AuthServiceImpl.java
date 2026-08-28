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

/** 소셜 계정 영속화와 DB 기반 refresh 세션 회전·폐기를 구현한다. 토큰 원문은 저장하지 않는다. */
@Service
public class AuthServiceImpl implements AuthService {
  private final UserAccountRepository users;
  private final SocialAccountRepository socialAccounts;
  private final RefreshSessionRepository refreshSessions;
  private final TokenService tokens;
  private final TransactionOperations transactions;

  /**
   * 저장소와 Spring 트랜잭션 관리자로 운영용 인증 서비스를 구성한다.
   *
   * @param users 내부 사용자 저장소
   * @param socialAccounts 외부 신원 연결 저장소
   * @param refreshSessions refresh 세션 저장소
   * @param tokens 자체 JWT 발급·검증기
   * @param transactionManager 로그인 경합 재시도에 사용할 트랜잭션 관리자
   */
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

  /**
   * 테스트에서 로그인 트랜잭션 실행 방식을 직접 주입한다.
   *
   * @param users 내부 사용자 저장소
   * @param socialAccounts 외부 신원 연결 저장소
   * @param refreshSessions refresh 세션 저장소
   * @param tokens 자체 JWT 발급·검증기
   * @param transactions 로그인 단위를 실행할 트랜잭션 연산자
   */
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

  /**
   * {@inheritDoc}
   *
   * <p>소셜 계정 생성의 유일성 경합이 발생하면 실패한 트랜잭션을 끝내고 별도 트랜잭션에서 승자 행을 재조회한다.
   */
  @Override
  public AuthResult login(GoogleIdentity identity) {
    try {
      return transactions.execute(status -> loginInTransaction(identity));
    } catch (DataIntegrityViolationException exception) {
      return transactions.execute(status -> loginExistingInTransaction(identity));
    }
  }

  /**
   * 한 트랜잭션에서 소셜 계정을 조회·생성하고 최신 표시 정보와 refresh 세션을 저장한다.
   *
   * @param identity 검증을 마친 Google 신원
   * @return 발급 토큰과 이 트랜잭션의 신규 사용자 여부
   */
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

  /**
   * 소셜 계정 unique 경합에서 승리한 행을 새 트랜잭션으로 재조회해 로그인한다.
   *
   * @param identity 검증을 마친 Google 신원
   * @return 기존 사용자용 발급 토큰
   * @throws ApiException 경합 뒤에도 연결 계정을 찾을 수 없는 경우
   */
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

  /**
   * {@inheritDoc}
   *
   * <p>digest 행을 비관적 쓰기 잠금으로 조회하므로 같은 refresh 토큰의 동시 요청 중 하나만 회전에 성공한다.
   */
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

  /** {@inheritDoc} */
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

  /**
   * access·refresh 토큰을 발급하고 refresh 원문 대신 digest와 만료 시각만 저장한다.
   *
   * @param userId 토큰 subject가 될 내부 사용자 ID
   * @param isNew 신규 사용자 여부
   * @return 발급 토큰과 신규 사용자 여부
   */
  private AuthResult issue(int userId, boolean isNew) {
    String refresh = tokens.issueRefresh(userId);
    refreshSessions.save(
        new RefreshSession(
            userId, tokens.digest(refresh), tokens.decodeRefresh(refresh).getExpiresAt()));
    return new AuthResult(tokens.issueAccess(userId), refresh, isNew);
  }

  /**
   * @return 사용할 수 없는 refresh 토큰을 나타내는 {@code INVALID_REFRESH_TOKEN} 401 예외
   */
  private ApiException invalidRefresh() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "다시 로그인해 주세요.");
  }
}

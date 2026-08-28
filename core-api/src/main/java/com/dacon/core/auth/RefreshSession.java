package com.dacon.core.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** refresh 토큰 원문 대신 digest, 만료·폐기 시각과 회전 후속 세션 ID를 저장한다. */
@Entity
@Table(name = "refresh_sessions")
class RefreshSession {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Integer userId;
  private String tokenDigest;
  private Instant expiresAt;
  private Instant revokedAt;
  private Long replacedById;

  /** JPA가 영속 상태를 복원할 때만 사용하는 생성자다. */
  protected RefreshSession() {}

  /**
   * 아직 폐기되지 않은 신규 refresh 세션을 만든다.
   *
   * @param userId 세션 소유 사용자 ID
   * @param tokenDigest refresh JWT 원문의 SHA-256 digest
   * @param expiresAt JWT claim과 같은 만료 시각
   */
  RefreshSession(int userId, String tokenDigest, Instant expiresAt) {
    this.userId = userId;
    this.tokenDigest = tokenDigest;
    this.expiresAt = expiresAt;
  }

  /** 현재 session을 폐기하고 후속 session ID를 연결한다. */
  void replaceWith(long replacementId, Instant now) {
    revokedAt = now;
    replacedById = replacementId;
  }

  /** 현재 session을 지정 시각에 폐기한다. */
  void revoke(Instant now) {
    revokedAt = now;
  }

  /** 지정 시각에 아직 사용 가능한 session인지 반환한다. */
  boolean usableAt(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }

  /** session ID를 반환한다. */
  long id() {
    return id;
  }

  /** session 소유 사용자 ID를 반환한다. */
  int userId() {
    return userId;
  }
}

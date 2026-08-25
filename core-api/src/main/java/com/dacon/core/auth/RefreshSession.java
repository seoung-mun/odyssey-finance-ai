package com.dacon.core.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** refresh token 원문 대신 digest와 회전 상태를 저장한다. */
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

  /** JPA가 기존 refresh session을 복원할 때 사용한다. */
  protected RefreshSession() {}

  /** 사용자, digest와 만료 시각으로 새 session을 만든다. */
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

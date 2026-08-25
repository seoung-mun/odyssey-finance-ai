package com.dacon.core.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

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

  protected RefreshSession() {}

  RefreshSession(int userId, String tokenDigest, Instant expiresAt) {
    this.userId = userId;
    this.tokenDigest = tokenDigest;
    this.expiresAt = expiresAt;
  }

  void replaceWith(long replacementId, Instant now) {
    revokedAt = now;
    replacedById = replacementId;
  }

  void revoke(Instant now) {
    revokedAt = now;
  }

  boolean usableAt(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }

  long id() {
    return id;
  }

  int userId() {
    return userId;
  }
}

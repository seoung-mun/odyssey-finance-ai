package com.dacon.core.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Google subject와 내부 사용자 ID의 연결을 저장한다. */
@Entity
@Table(name = "social_accounts")
public class SocialAccount {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  private Integer userId;
  private String provider;
  private String providerSubject;
  private String email;
  private boolean emailVerified;
  private String displayName;
  private String profileImageUrl;
  private Instant lastLoginAt;

  /** JPA가 기존 소셜 계정을 복원할 때 사용한다. */
  protected SocialAccount() {}

  /** 신규 Google 계정을 검증된 사용자에게 연결한다. */
  SocialAccount(int userId, GoogleIdentity identity) {
    this.userId = userId;
    provider = "GOOGLE";
    update(identity);
  }

  /** 검증된 최신 Google 표시 정보를 반영하고 로그인 시각을 갱신한다. */
  void update(GoogleIdentity identity) {
    providerSubject = identity.subject();
    email = identity.email();
    emailVerified = true;
    displayName = identity.displayName();
    profileImageUrl = identity.profileImageUrl();
    lastLoginAt = Instant.now();
  }

  /** 연결된 내부 사용자 ID를 반환한다. */
  int userId() {
    return userId;
  }

  public String email() {
    return email;
  }

  public String displayName() {
    return displayName;
  }

  public String profileImageUrl() {
    return profileImageUrl;
  }
}

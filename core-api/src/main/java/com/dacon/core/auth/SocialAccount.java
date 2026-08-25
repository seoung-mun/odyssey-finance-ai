package com.dacon.core.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Google subject와 내부 사용자 ID의 유일한 연결 및 최근 표시 정보를 저장한다. */
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

  /** JPA가 영속 상태를 복원할 때만 사용하는 생성자다. */
  protected SocialAccount() {}

  /**
   * 신규 Google 계정을 내부 사용자에게 연결하고 현재 로그인 정보를 반영한다.
   *
   * @param userId 연결할 내부 사용자 ID
   * @param identity 검증을 마친 Google 신원
   */
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

  /**
   * 현재 사용자 응답에 표시할 검증된 이메일을 제공한다.
   *
   * @return 마지막으로 검증된 Google 이메일
   */
  public String email() {
    return email;
  }

  /**
   * 현재 사용자 응답에 표시할 선택적 이름을 제공한다.
   *
   * @return Google 표시 이름, claim이 없으면 {@code null}
   */
  public String displayName() {
    return displayName;
  }

  /**
   * 현재 사용자 응답에 표시할 선택적 프로필 이미지를 제공한다.
   *
   * @return Google 프로필 이미지 URL, claim이 없으면 {@code null}
   */
  public String profileImageUrl() {
    return profileImageUrl;
  }
}

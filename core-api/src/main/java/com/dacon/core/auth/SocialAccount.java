package com.dacon.core.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "social_accounts")
class SocialAccount {
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

  protected SocialAccount() {}

  SocialAccount(int userId, GoogleIdentity identity) {
    this.userId = userId;
    provider = "GOOGLE";
    update(identity);
  }

  void update(GoogleIdentity identity) {
    providerSubject = identity.subject();
    email = identity.email();
    emailVerified = true;
    displayName = identity.displayName();
    profileImageUrl = identity.profileImageUrl();
    lastLoginAt = Instant.now();
  }

  int userId() {
    return userId;
  }
}

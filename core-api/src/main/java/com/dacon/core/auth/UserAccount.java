package com.dacon.core.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 모든 사용자 소유 데이터의 기준이 되는 users 행이다. */
@Entity
@Table(name = "users")
public class UserAccount {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  private Instant sampleDataLoadedAt;
  private Instant createdAt;
  private Instant updatedAt;

  /** JPA가 기존 사용자를 복원할 때 사용한다. */
  public UserAccount() {
    createdAt = Instant.now();
    updatedAt = createdAt;
  }

  /** DB 사용자 ID를 반환한다. */
  public int id() {
    return id;
  }

  /** 샘플 적재 시각을 반환한다. */
  public Instant sampleDataLoadedAt() {
    return sampleDataLoadedAt;
  }

  /** 샘플 적재를 완료 처리한다. */
  public void markSampleLoaded(Instant now) {
    sampleDataLoadedAt = now;
    updatedAt = now;
  }
}

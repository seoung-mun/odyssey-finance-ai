package com.dacon.core.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 모든 사용자 소유 데이터가 참조하는 내부 계정을 나타낸다. */
@Entity
@Table(name = "users")
public class UserAccount {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  private Instant createdAt;
  private Instant updatedAt;

  /** 신규 계정의 생성·수정 시각을 현재 시각으로 초기화한다. JPA 복원에도 사용된다. */
  public UserAccount() {
    createdAt = Instant.now();
    updatedAt = createdAt;
  }

  /**
   * 모든 사용자 소유 데이터의 외래키 기준을 제공한다.
   *
   * @return DB가 할당한 사용자 ID
   */
  public int id() {
    return id;
  }
}

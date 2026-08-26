package com.dacon.core.goal;

import com.dacon.core.auth.UserAccount;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** 계획 엔진에서 확정 지출로 다루는 사용자 소유 예정지출을 저장한다. */
@Entity
@Table(name = "scheduled_expenses")
public class ScheduledExpense {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private UserAccount user;

  private String name;
  private long amount;
  private LocalDate scheduledDate;
  private String status;
  private Instant createdAt;
  private Instant updatedAt;

  /** JPA가 영속 상태를 복원할 때만 사용하는 생성자다. */
  protected ScheduledExpense() {}

  /**
   * PLANNED 상태의 예정지출을 만든다.
   *
   * @param user 예정지출 소유 사용자
   * @param name 표시할 지출 이름
   * @param amount 확정 예정 금액(원)
   * @param scheduledDate 지출 예정일
   */
  public ScheduledExpense(UserAccount user, String name, long amount, LocalDate scheduledDate) {
    this.user = user;
    this.name = name;
    this.amount = amount;
    this.scheduledDate = scheduledDate;
    status = "PLANNED";
    createdAt = Instant.now();
    updatedAt = createdAt;
  }

  /**
   * 거래 연결과 API 응답에 사용할 예정지출 식별자를 제공한다.
   *
   * @return DB가 할당한 예정지출 ID
   */
  public int id() {
    return id;
  }

  public int userId() {
    return user.id();
  }

  /**
   * 계획 계산에서 확정 지출로 차감할 금액을 제공한다.
   *
   * @return 예정 금액(원)
   */
  public long amount() {
    return amount;
  }

  /**
   * 확정 지출을 반영할 달을 결정하는 예정일을 제공한다.
   *
   * @return 지출 예정일
   */
  public LocalDate scheduledDate() {
    return scheduledDate;
  }

  String name() {
    return name;
  }

  String status() {
    return status;
  }

  void update(String name, Long amount, LocalDate scheduledDate, String status) {
    if (name != null) this.name = name.trim();
    if (amount != null) this.amount = amount;
    if (scheduledDate != null) this.scheduledDate = scheduledDate;
    if (status != null) this.status = status;
    updatedAt = Instant.now();
  }
}

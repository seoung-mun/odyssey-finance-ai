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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;

/** 사용자 금융 목표의 원금·기한·상태와 소비 재계획 억제 기한을 저장한다. */
@Entity
@Table(
    name = "financial_goals",
    uniqueConstraints = @UniqueConstraint(columnNames = {"id", "user_id"}))
public class FinancialGoal {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private UserAccount user;

  private String name;
  private long targetAmount;
  private long currentSavedAmount;
  private LocalDate targetDate;
  private String status;
  private LocalDate spendingReplanSuppressedUntil;
  private Instant createdAt;
  private Instant updatedAt;

  /** JPA가 영속 상태를 복원할 때만 사용하는 생성자다. */
  protected FinancialGoal() {}

  /**
   * 이름의 앞뒤 공백을 제거하고 ACTIVE 상태인 신규 목표를 만든다.
   *
   * @param user 목표 소유 사용자
   * @param name 표시할 목표 이름
   * @param targetAmount 목표 금액(원)
   * @param currentSavedAmount 이미 저축한 금액(원)
   * @param targetDate 목표일
   */
  public FinancialGoal(
      UserAccount user,
      String name,
      long targetAmount,
      long currentSavedAmount,
      LocalDate targetDate) {
    this.user = user;
    this.name = name.trim();
    this.targetAmount = targetAmount;
    this.currentSavedAmount = currentSavedAmount;
    this.targetDate = targetDate;
    status = "ACTIVE";
    createdAt = Instant.now();
    updatedAt = createdAt;
  }

  /**
   * 영속 목표의 식별자를 제공한다.
   *
   * @return DB가 할당한 목표 ID
   */
  public int id() {
    return id;
  }

  /**
   * 조회와 계산에서 사용할 목표 소유자를 식별한다.
   *
   * @return 목표 소유 사용자 ID
   */
  public int userId() {
    return user.id();
  }

  /**
   * 화면에 표시할 정규화된 목표 이름을 제공한다.
   *
   * @return 앞뒤 공백이 제거된 목표 이름
   */
  public String name() {
    return name;
  }

  /**
   * 사용자가 달성하려는 총 금액을 제공한다.
   *
   * @return 목표 금액(원)
   */
  public long targetAmount() {
    return targetAmount;
  }

  /**
   * 계획 계산에서 이미 확보한 금액을 제공한다.
   *
   * @return 현재까지 저축한 금액(원)
   */
  public long currentSavedAmount() {
    return currentSavedAmount;
  }

  /**
   * 목표 계산이 도달해야 할 마지막 날짜를 제공한다.
   *
   * @return 목표일
   */
  public LocalDate targetDate() {
    return targetDate;
  }

  /**
   * 목표가 현재 진행 중인지 종료됐는지 구분한다.
   *
   * @return ACTIVE·ACHIEVED·CANCELLED 중 현재 상태
   */
  public String status() {
    return status;
  }

  /**
   * 반복 소비 이탈 알림을 보류할 기한을 제공한다.
   *
   * @return 소비 이탈 재계획을 억제하는 마지막 날짜, 설정하지 않았으면 {@code null}
   */
  public LocalDate spendingReplanSuppressedUntil() {
    return spendingReplanSuppressedUntil;
  }

  /**
   * 목표 이력을 정렬할 생성 시각을 제공한다.
   *
   * @return 목표 생성 시각
   */
  public Instant createdAt() {
    return createdAt;
  }

  void update(
      String name,
      Long targetAmount,
      Long currentSavedAmount,
      LocalDate targetDate,
      String status) {
    if (name != null) this.name = name.trim();
    if (targetAmount != null) this.targetAmount = targetAmount;
    if (currentSavedAmount != null) this.currentSavedAmount = currentSavedAmount;
    if (targetDate != null) this.targetDate = targetDate;
    if (status != null) this.status = status;
    updatedAt = Instant.now();
  }

  public void suppressSpendingReplanUntil(LocalDate until) {
    spendingReplanSuppressedUntil = until;
    updatedAt = Instant.now();
  }
}

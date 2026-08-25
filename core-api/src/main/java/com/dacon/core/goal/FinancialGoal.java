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

/** 사용자 금융 목표와 users 외래키 관계를 저장한다. */
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

  /** JPA 복원용 생성자다. */
  protected FinancialGoal() {}

  /** 활성 목표를 생성한다. */
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

  public int id() {
    return id;
  }

  public int userId() {
    return user.id();
  }

  public String name() {
    return name;
  }

  public long targetAmount() {
    return targetAmount;
  }

  public long currentSavedAmount() {
    return currentSavedAmount;
  }

  public LocalDate targetDate() {
    return targetDate;
  }

  public String status() {
    return status;
  }

  public LocalDate spendingReplanSuppressedUntil() {
    return spendingReplanSuppressedUntil;
  }

  public Instant createdAt() {
    return createdAt;
  }
}

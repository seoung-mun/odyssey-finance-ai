package com.dacon.core.user.entity;

import com.dacon.core.auth.UserAccount;
import com.dacon.core.user.dto.FinancialProfileInput;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** 사용자의 현재 월 금융정보를 DB schema와 동일하게 저장한다. */
@Entity
@Table(name = "financial_profiles")
public class FinancialProfile {
  @Id private Integer userId;

  @MapsId
  @OneToOne(optional = false, fetch = jakarta.persistence.FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private UserAccount user;

  private long monthlyIncome;
  private long monthlyFixedCost;

  @Enumerated(EnumType.STRING)
  private SpendingFloorMode spendingFloorMode = SpendingFloorMode.OFF;

  private Long customMonthlyVariableFloor;
  private Instant updatedAt;

  /** JPA가 기존 금융 프로필을 복원할 때 사용한다. */
  protected FinancialProfile() {}

  /** 사용자 ID를 기본키로 신규 금융 프로필을 만든다. */
  public FinancialProfile(UserAccount user) {
    this.user = user;
  }

  /** 검증된 입력을 반영하고 수정 시각을 갱신한다. */
  public void update(FinancialProfileInput input) {
    monthlyIncome = input.monthlyIncome();
    monthlyFixedCost = input.monthlyFixedCost();
    spendingFloorMode = input.spendingFloorMode();
    customMonthlyVariableFloor = input.customMonthlyVariableFloor();
    updatedAt = Instant.now();
  }

  /** 저장값이 요청값과 동일한지 반환한다. */
  public boolean matches(FinancialProfileInput input) {
    return monthlyIncome == input.monthlyIncome()
        && monthlyFixedCost == input.monthlyFixedCost()
        && spendingFloorMode == input.spendingFloorMode()
        && java.util.Objects.equals(customMonthlyVariableFloor, input.customMonthlyVariableFloor());
  }

  /** 월소득을 반환한다. */
  public long monthlyIncome() {
    return monthlyIncome;
  }

  /** 월고정비를 반환한다. */
  public long monthlyFixedCost() {
    return monthlyFixedCost;
  }

  /** 소비 하한 모드를 반환한다. */
  public SpendingFloorMode spendingFloorMode() {
    return spendingFloorMode;
  }

  /** 사용자 지정 월 소비 하한을 반환한다. */
  public Long customMonthlyVariableFloor() {
    return customMonthlyVariableFloor;
  }

  /** 마지막 수정 시각을 반환한다. */
  public Instant updatedAt() {
    return updatedAt;
  }
}

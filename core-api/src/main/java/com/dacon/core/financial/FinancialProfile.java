package com.dacon.core.financial;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "financial_profiles")
public class FinancialProfile {
  @Id private Integer userId;
  private long monthlyIncome;
  private long monthlyFixedCost;

  @Enumerated(EnumType.STRING)
  private SpendingFloorMode spendingFloorMode = SpendingFloorMode.OFF;

  private Long customMonthlyVariableFloor;
  private Instant updatedAt;

  protected FinancialProfile() {}

  FinancialProfile(int userId) {
    this.userId = userId;
  }

  void update(FinancialProfileInput input) {
    monthlyIncome = input.monthlyIncome();
    monthlyFixedCost = input.monthlyFixedCost();
    spendingFloorMode = input.spendingFloorMode();
    customMonthlyVariableFloor = input.customMonthlyVariableFloor();
    updatedAt = Instant.now();
  }

  boolean matches(FinancialProfileInput input) {
    return monthlyIncome == input.monthlyIncome()
        && monthlyFixedCost == input.monthlyFixedCost()
        && spendingFloorMode == input.spendingFloorMode()
        && java.util.Objects.equals(customMonthlyVariableFloor, input.customMonthlyVariableFloor());
  }

  FinancialProfileResponse response() {
    return new FinancialProfileResponse(
        monthlyIncome,
        monthlyFixedCost,
        spendingFloorMode,
        customMonthlyVariableFloor,
        updatedAt,
        null,
        ReplanOutcome.NOT_REQUIRED,
        null,
        null);
  }
}

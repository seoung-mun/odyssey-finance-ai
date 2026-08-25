package com.dacon.core.financial;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record FinancialProfileInput(
    @PositiveOrZero long monthlyIncome,
    @PositiveOrZero long monthlyFixedCost,
    @NotNull SpendingFloorMode spendingFloorMode,
    @PositiveOrZero Long customMonthlyVariableFloor) {

  @AssertTrue(message = "CUSTOM 모드에서 소비 하한은 필수입니다")
  public boolean isCustomFloorPresent() {
    return spendingFloorMode != SpendingFloorMode.CUSTOM || customMonthlyVariableFloor != null;
  }

  @AssertTrue(message = "OFF/AUTO 모드에서 소비 하한은 비워야 합니다")
  public boolean isNonCustomFloorAbsent() {
    return spendingFloorMode == SpendingFloorMode.CUSTOM || customMonthlyVariableFloor == null;
  }
}

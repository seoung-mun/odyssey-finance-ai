package com.dacon.core.user.dto;

import com.dacon.core.user.entity.SpendingFloorMode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 월 금융정보와 소비 하한 입력이다. 모든 금액은 원 단위다.
 *
 * @param monthlyIncome 0 이상의 월소득
 * @param monthlyFixedCost 0 이상의 월고정비
 * @param spendingFloorMode 소비 하한 적용 방식
 * @param customMonthlyVariableFloor CUSTOM 모드의 0 이상 월 유동지출 하한
 */
public record FinancialProfileInput(
    @NotNull @PositiveOrZero Long monthlyIncome,
    @NotNull @PositiveOrZero Long monthlyFixedCost,
    @NotNull SpendingFloorMode spendingFloorMode,
    @PositiveOrZero Long customMonthlyVariableFloor) {
  /**
   * CUSTOM 모드에 사용자 지정 하한이 제공됐는지 검증한다.
   *
   * @return CUSTOM이 아니거나 하한이 있으면 {@code true}
   */
  @AssertTrue(message = "CUSTOM 모드에서 소비 하한은 필수입니다")
  public boolean isCustomFloorPresent() {
    return spendingFloorMode != SpendingFloorMode.CUSTOM || customMonthlyVariableFloor != null;
  }

  /**
   * OFF·AUTO 모드에 의미 없는 사용자 지정 하한이 섞이지 않았는지 검증한다.
   *
   * @return CUSTOM이거나 사용자 지정 하한이 없으면 {@code true}
   */
  @AssertTrue(message = "OFF/AUTO 모드에서 소비 하한은 비워야 합니다")
  public boolean isNonCustomFloorAbsent() {
    return spendingFloorMode == SpendingFloorMode.CUSTOM || customMonthlyVariableFloor == null;
  }
}

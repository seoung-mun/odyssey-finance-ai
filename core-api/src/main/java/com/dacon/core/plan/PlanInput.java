package com.dacon.core.plan;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;

/** DB에서 확정한 계산 입력 snapshot이다. */
public record PlanInput(
    int userId,
    int goalId,
    String goalName,
    long targetAmount,
    long currentSavedAmount,
    LocalDate targetDate,
    long monthlyIncome,
    long monthlyFixedCost,
    String floorMode,
    Long customFloor,
    List<Long> history,
    List<ScheduledInput> scheduledExpenses,
    int horizonMonths,
    List<Double> periodRatios,
    long availableVariableBudget,
    long currentMonthSpent,
    long currentAverage,
    JsonNode policySnapshot,
    boolean profileComplete,
    String planState) {
  /** 계산용 예정지출 한 건이다. */
  public record ScheduledInput(int monthIndex, long amount) {}
}

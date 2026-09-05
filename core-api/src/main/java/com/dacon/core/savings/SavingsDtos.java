package com.dacon.core.savings;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class SavingsDtos {
  private SavingsDtos() {}

  public record ConditionResponse(long conditionId, String label, BigDecimal bonusRate) {}

  public record RecommendationResponse(
      long productId,
      long optionId,
      String finCoNo,
      String finPrdtCd,
      String bankName,
      String productName,
      String reserveType,
      int termMonths,
      BigDecimal baseRate,
      BigDecimal maximumRate,
      long monthlySavings,
      long pretaxInterest,
      long acceleratedMonths,
      List<ConditionResponse> availableConditions) {}

  public record RecommendationListResponse(
      LocalDate planAsOfDate,
      int remainingMonths,
      long monthlySavings,
      List<RecommendationResponse> recommendations) {}

  public record WhatIfRequest(
      @NotNull @Positive Long optionId,
      @NotNull @Size(max = 20) List<@Positive Long> conditionIds) {}

  public record WhatIfResponse(
      boolean calculable,
      String message,
      long productId,
      long optionId,
      Integer termMonths,
      BigDecimal appliedRate,
      Long monthlySavings,
      Long pretaxInterest,
      Long acceleratedMonths,
      List<ConditionResponse> appliedConditions) {}
}

package com.dacon.core.policy;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.YearMonth;

public final class PolicyBenefitDtos {
  private PolicyBenefitDtos() {}

  public record ConfirmPolicyBenefitRequest(
      @Positive int goalId,
      @AssertTrue boolean institutionConfirmed,
      @Positive @Max(1_000_000_000_000_000L) long amountWon,
      @NotNull @JsonFormat(pattern = "yyyy-MM") YearMonth startYearMonth,
      @JsonFormat(pattern = "yyyy-MM") YearMonth endYearMonth) {}

  public record PolicyBenefitResponse(
      long id,
      int goalId,
      long policyVersionId,
      String adjustmentType,
      long amountWon,
      @JsonFormat(pattern = "yyyy-MM") YearMonth startYearMonth,
      @JsonFormat(pattern = "yyyy-MM") YearMonth endYearMonth,
      String status,
      Instant confirmedAt) {}
}

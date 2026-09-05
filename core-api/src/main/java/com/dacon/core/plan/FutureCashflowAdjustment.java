package com.dacon.core.plan;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.time.YearMonth;

/** 계획 생성 시점에 확정된 정책 혜택을 원본 금융 상태와 분리해 보존하는 미래 현금흐름 조정이다. */
public record FutureCashflowAdjustment(
    String source,
    long policyBenefitId,
    long policyVersionId,
    String adjustmentType,
    long amountWon,
    @JsonFormat(pattern = "yyyy-MM") YearMonth startYearMonth,
    @JsonFormat(pattern = "yyyy-MM") YearMonth endYearMonth,
    Instant confirmedAt) {
  public static final String POLICY_BENEFIT_SOURCE = "POLICY_BENEFIT";
  public static final String ONE_TIME_FUNDING = "ONE_TIME_FUNDING";
  public static final String MONTHLY_EXPENSE_REDUCTION = "MONTHLY_EXPENSE_REDUCTION";

  /** DB 불변식이 깨진 조정은 자동 보정하지 않고 계획 입력 경계에서 거부한다. */
  public FutureCashflowAdjustment {
    if (!POLICY_BENEFIT_SOURCE.equals(source)
        || policyBenefitId <= 0
        || policyVersionId <= 0
        || amountWon <= 0
        || startYearMonth == null
        || confirmedAt == null) {
      throw new IllegalArgumentException("유효하지 않은 미래 현금흐름 조정입니다.");
    }
    if (ONE_TIME_FUNDING.equals(adjustmentType)) {
      if (endYearMonth != null) {
        throw new IllegalArgumentException("일시 지원에는 종료월을 지정할 수 없습니다.");
      }
    } else if (MONTHLY_EXPENSE_REDUCTION.equals(adjustmentType)) {
      if (endYearMonth == null || endYearMonth.isBefore(startYearMonth)) {
        throw new IllegalArgumentException("월별 지원 기간이 유효하지 않습니다.");
      }
    } else {
      throw new IllegalArgumentException("지원하지 않는 미래 현금흐름 조정 유형입니다.");
    }
  }
}

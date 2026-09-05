package com.dacon.core.savings;

import com.dacon.core.error.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;

final class SavingsCalculator {
  private SavingsCalculator() {}

  static long monthlySavings(long income, long fixedCost, long recommendedSpending) {
    try {
      long result = Math.subtractExact(Math.subtractExact(income, fixedCost), recommendedSpending);
      if (result <= 0) {
        throw invalidSavings();
      }
      return result;
    } catch (ArithmeticException exception) {
      throw invalidSavings();
    }
  }

  static int remainingMonths(LocalDate asOfDate, LocalDate targetDate) {
    long months =
        ChronoUnit.MONTHS.between(YearMonth.from(asOfDate), YearMonth.from(targetDate)) + 1;
    if (months < 1 || months > 120) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SAVINGS_HORIZON", "현재 계획의 남은 기간을 계산할 수 없습니다.");
    }
    return Math.toIntExact(months);
  }

  static long pretaxInterest(long monthlySavings, int termMonths, BigDecimal annualRate) {
    try {
      long monthFactor = Math.multiplyExact((long) termMonths, termMonths + 1L) / 2L;
      return BigDecimal.valueOf(monthlySavings)
          .multiply(BigDecimal.valueOf(monthFactor))
          .multiply(annualRate)
          .divide(BigDecimal.valueOf(1200), 0, RoundingMode.FLOOR)
          .longValueExact();
    } catch (ArithmeticException exception) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY, "SAVINGS_CALCULATION_OVERFLOW", "적금 계산 범위를 확인해 주세요.");
    }
  }

  private static ApiException invalidSavings() {
    return new ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MONTHLY_SAVINGS", "계획의 월저축액을 계산할 수 없습니다.");
  }
}

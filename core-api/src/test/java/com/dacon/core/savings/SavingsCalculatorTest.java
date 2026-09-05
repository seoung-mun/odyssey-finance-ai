package com.dacon.core.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dacon.core.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SavingsCalculatorTest {
  @Test
  void derivesMonthlySavingsFromTheActivePlanSnapshotAndSelectedSpending() {
    assertThat(SavingsCalculator.monthlySavings(4_000_000, 1_200_000, 1_800_000))
        .isEqualTo(1_000_000);
  }

  @Test
  void rejectsNonPositiveOrOverflowedMonthlySavings() {
    assertThatThrownBy(() -> SavingsCalculator.monthlySavings(3_000_000, 1_000_000, 2_000_000))
        .isInstanceOf(ApiException.class)
        .extracting(exception -> ((ApiException) exception).code())
        .isEqualTo("INVALID_MONTHLY_SAVINGS");
    assertThatThrownBy(() -> SavingsCalculator.monthlySavings(Long.MIN_VALUE, 1, 1))
        .isInstanceOf(ApiException.class)
        .extracting(exception -> ((ApiException) exception).code())
        .isEqualTo("INVALID_MONTHLY_SAVINGS");
  }

  @Test
  void countsRemainingMonthsInclusivelyFromPlanAsOfMonth() {
    assertThat(
            SavingsCalculator.remainingMonths(LocalDate.of(2026, 9, 30), LocalDate.of(2027, 8, 1)))
        .isEqualTo(12);
  }

  @Test
  void floorsPretaxSimpleInterestInWon() {
    assertThat(SavingsCalculator.pretaxInterest(1_000_000, 12, new BigDecimal("3.0")))
        .isEqualTo(195_000);
  }
}

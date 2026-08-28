package com.dacon.core.plan;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;

/** 자동 재계획 임계값을 원 단위 정수 산술로 판정한다. */
public final class ReplanTriggerPolicy {
  private ReplanTriggerPolicy() {}

  public static boolean shock(List<Long> previousPayments, long monthlyBudget, long amount) {
    if (previousPayments.isEmpty()) {
      return false;
    }
    List<Long> sorted = previousPayments.stream().sorted().toList();
    int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
    return amount >= shockThreshold(sorted.get(index), monthlyBudget);
  }

  public static long shockThreshold(long p95, long monthlyBudget) {
    long budgetThreshold =
        BigInteger.valueOf(monthlyBudget)
            .multiply(BigInteger.valueOf(15))
            .add(BigInteger.valueOf(99))
            .divide(BigInteger.valueOf(100))
            .longValueExact();
    return Math.max(p95, budgetThreshold);
  }

  public static boolean drift(LocalDate date, long monthlyBudget, long actual) {
    int day = date.getDayOfMonth();
    if ((day != 7 && day != 14 && day != 21) || monthlyBudget <= 0) {
      return false;
    }
    int checkpointPercent = day == 7 ? 25 : day == 14 ? 50 : 75;
    return BigInteger.valueOf(actual)
            .multiply(BigInteger.valueOf(100))
            .compareTo(
                BigInteger.valueOf(monthlyBudget)
                    .multiply(BigInteger.valueOf(checkpointPercent * 120L))
                    .divide(BigInteger.valueOf(100)))
        > 0;
  }

  public static long plannedCumulative(long monthlyBudget, LocalDate date) {
    int day = date.getDayOfMonth();
    int checkpointPercent = day == 7 ? 25 : day == 14 ? 50 : day == 21 ? 75 : 0;
    return BigInteger.valueOf(monthlyBudget)
        .multiply(BigInteger.valueOf(checkpointPercent))
        .divide(BigInteger.valueOf(100))
        .longValueExact();
  }
}

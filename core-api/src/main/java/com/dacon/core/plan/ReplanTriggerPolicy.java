package com.dacon.core.plan;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;

/** 자동 재계획 임계값을 원 단위 정수 산술로 판정한다. */
public final class ReplanTriggerPolicy {
  private ReplanTriggerPolicy() {}

  public static boolean shock(List<Long> previousPayments, long amount) {
    if (previousPayments.isEmpty()) {
      return false;
    }
    List<Long> sorted = previousPayments.stream().sorted().toList();
    int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
    return BigInteger.valueOf(amount)
            .multiply(BigInteger.valueOf(100))
            .compareTo(BigInteger.valueOf(sorted.get(index)).multiply(BigInteger.valueOf(115)))
        >= 0;
  }

  public static long shockThreshold(long p95) {
    BigInteger numerator = BigInteger.valueOf(p95).multiply(BigInteger.valueOf(115));
    return numerator.add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100)).longValueExact();
  }

  public static boolean drift(LocalDate date, long monthlyBudget, long actual) {
    int day = date.getDayOfMonth();
    if ((day != 7 && day != 14 && day != 21) || monthlyBudget <= 0) {
      return false;
    }
    return BigInteger.valueOf(actual)
            .multiply(BigInteger.valueOf(date.lengthOfMonth() * 100L))
            .compareTo(BigInteger.valueOf(monthlyBudget).multiply(BigInteger.valueOf(day * 120L)))
        >= 0;
  }

  public static long plannedCumulative(long monthlyBudget, LocalDate date) {
    return BigInteger.valueOf(monthlyBudget)
        .multiply(BigInteger.valueOf(date.getDayOfMonth()))
        .divide(BigInteger.valueOf(date.lengthOfMonth()))
        .longValueExact();
  }
}

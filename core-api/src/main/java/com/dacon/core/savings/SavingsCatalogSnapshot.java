package com.dacon.core.savings;

import java.math.BigDecimal;
import java.util.List;

record SavingsCatalogSnapshot(List<Product> products, List<Option> options) {
  record Product(
      String finCoNo,
      String finPrdtCd,
      String dclsMonth,
      String bankName,
      String productName,
      String joinWay,
      String maturityInterest,
      String specialCondition,
      String joinDeny,
      String joinMember,
      Long maxLimit) {}

  record Option(
      String finCoNo,
      String finPrdtCd,
      String rateType,
      String rateTypeName,
      String reserveType,
      String reserveTypeName,
      int termMonths,
      BigDecimal baseRate,
      BigDecimal maximumRate) {}

  record Condition(String label, BigDecimal bonusRate, String rawFragment) {}
}

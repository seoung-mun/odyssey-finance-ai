package com.dacon.core.policy;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;

/** 정책 검색과 기관 확인이 공유하는 지역·연령 자격 판정기다. */
final class PolicyEligibilityEvaluator {
  private PolicyEligibilityEvaluator() {}

  static boolean isEligible(
      String applicationDecision,
      String regionScope,
      List<String> regionCodes,
      Integer ageMin,
      Integer ageMax,
      String userRegionCode,
      LocalDate birthDate,
      LocalDate evaluationDate) {
    if (!"ALLOW".equals(applicationDecision)) {
      return false;
    }
    boolean regionEligible =
        "NATIONAL".equals(regionScope)
            || ("LOCAL".equals(regionScope)
                && userRegionCode != null
                && regionCodes != null
                && regionCodes.contains(userRegionCode));
    if (!regionEligible) {
      return false;
    }
    if (ageMin == null && ageMax == null) {
      return true;
    }
    if (ageMin == null
        || ageMax == null
        || birthDate == null
        || birthDate.isAfter(evaluationDate)) {
      return false;
    }
    int age = Period.between(birthDate, evaluationDate).getYears();
    return age >= ageMin && age <= ageMax;
  }
}

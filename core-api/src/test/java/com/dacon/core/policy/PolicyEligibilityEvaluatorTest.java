package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyEligibilityEvaluatorTest {
  private static final LocalDate TODAY = LocalDate.of(2026, 9, 4);

  @Test
  void requiresAllowAndMatchesLocalRegion() {
    assertThat(eligible("DENY", "NATIONAL", List.of(), null, null, null, null)).isFalse();
    assertThat(eligible("ALLOW", "LOCAL", List.of("11110"), null, null, "11110", null)).isTrue();
    assertThat(eligible("ALLOW", "LOCAL", List.of("11110"), null, null, "26110", null)).isFalse();
  }

  @Test
  void evaluatesInclusiveFullYearAgeAndRejectsIncompleteBounds() {
    assertThat(eligible("ALLOW", "NATIONAL", List.of(), 20, 26, null, LocalDate.of(2000, 9, 4)))
        .isTrue();
    assertThat(eligible("ALLOW", "NATIONAL", List.of(), 27, 30, null, LocalDate.of(2000, 9, 5)))
        .isFalse();
    assertThat(eligible("ALLOW", "NATIONAL", List.of(), 20, null, null, LocalDate.of(2000, 1, 1)))
        .isFalse();
  }

  private boolean eligible(
      String decision,
      String scope,
      List<String> regions,
      Integer min,
      Integer max,
      String userRegion,
      LocalDate birthDate) {
    return PolicyEligibilityEvaluator.isEligible(
        decision, scope, regions, min, max, userRegion, birthDate, TODAY);
  }
}

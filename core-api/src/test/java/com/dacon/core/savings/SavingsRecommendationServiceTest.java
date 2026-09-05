package com.dacon.core.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dacon.core.error.ApiException;
import com.dacon.core.plan.PlanOption;
import com.dacon.core.plan.PlanOptionRepository;
import com.dacon.core.plan.PlanVersion;
import com.dacon.core.plan.PlanVersionRepository;
import com.dacon.core.savings.SavingsDtos.WhatIfRequest;
import com.dacon.core.savings.SavingsRepository.ConditionRow;
import com.dacon.core.savings.SavingsRepository.OptionRow;
import com.dacon.core.savings.SavingsRepository.ProductRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SavingsRecommendationServiceTest {
  private final PlanVersionRepository plans = mock(PlanVersionRepository.class);
  private final PlanOptionRepository planOptions = mock(PlanOptionRepository.class);
  private final SavingsRepository savings = mock(SavingsRepository.class);
  private final SavingsRecommendationService service =
      new SavingsRecommendationService(plans, planOptions, savings);

  @BeforeEach
  void activePlan() {
    PlanVersion plan = mock(PlanVersion.class);
    PlanOption option = mock(PlanOption.class);
    when(plan.id()).thenReturn(11);
    when(plan.asOfDate()).thenReturn(LocalDate.of(2026, 9, 5));
    when(plan.targetDateSnapshot()).thenReturn(LocalDate.of(2027, 8, 31));
    when(plan.monthlyIncomeSnapshot()).thenReturn(4_000_000L);
    when(plan.monthlyFixedCostSnapshot()).thenReturn(1_200_000L);
    when(option.recommendedMonthlySpending()).thenReturn(1_800_000L);
    when(plans.findActiveByUserId(7)).thenReturn(Optional.of(plan));
    when(planOptions.findByPlanVersionIdAndSelectedAtIsNotNull(11)).thenReturn(Optional.of(option));
  }

  @Test
  void recommendationsUseBaseRateAndResolveCrossBankTiesByFullProductKey() {
    ProductRow laterBank = product(1, "002", "SAME", null);
    ProductRow firstBank = product(2, "001", "SAME", null);
    OptionRow optionOne = option(101, 1, 12, "3.0");
    OptionRow optionTwo = option(102, 2, 12, "3.0");
    when(savings.eligibleProducts()).thenReturn(List.of(laterBank, firstBank));
    when(savings.simpleOptions(1)).thenReturn(List.of(optionOne));
    when(savings.simpleOptions(2)).thenReturn(List.of(optionTwo));
    when(savings.ruleConditions(1)).thenReturn(List.of());
    when(savings.ruleConditions(2)).thenReturn(List.of());

    var response = service.recommendations(7);

    assertThat(response.monthlySavings()).isEqualTo(1_000_000);
    assertThat(response.remainingMonths()).isEqualTo(12);
    assertThat(response.recommendations())
        .extracting(value -> value.finCoNo())
        .containsExactly("001", "002");
    assertThat(response.recommendations())
        .extracting(value -> value.pretaxInterest())
        .containsOnly(195_000L);
  }

  @Test
  void maximumLimitNullIsEligibleAndTooSmallLimitIsExcluded() {
    ProductRow unlimited = product(1, "001", "A", null);
    ProductRow limited = product(2, "002", "B", 999_999L);
    when(savings.eligibleProducts()).thenReturn(List.of(unlimited, limited));
    when(savings.simpleOptions(1)).thenReturn(List.of(option(101, 1, 12, "3.0")));
    when(savings.ruleConditions(1)).thenReturn(List.of());

    assertThat(service.recommendations(7).recommendations())
        .extracting(value -> value.productId())
        .containsExactly(1L);
  }

  @Test
  void whatIfUsesOnlyServerResolvedRuleConditionsAndCapsMaximumRate() {
    ProductRow product = product(1, "001", "A", null);
    OptionRow option = new OptionRow(101, 1, "F", "정액적립식", 12, rate("3.0"), rate("3.5"));
    when(savings.product(1)).thenReturn(Optional.of(product));
    when(savings.simpleOption(1, 101)).thenReturn(Optional.of(option));
    when(savings.selectedRuleConditions(1, List.of(201L, 202L)))
        .thenReturn(
            List.of(
                new ConditionRow(201, 1, "급여이체", rate("0.4")),
                new ConditionRow(202, 1, "첫 거래", rate("0.3"))));

    var response = service.whatIf(7, 1, new WhatIfRequest(101L, List.of(201L, 202L)));

    assertThat(response.calculable()).isTrue();
    assertThat(response.appliedRate()).isEqualByComparingTo("3.5");
    assertThat(response.appliedConditions())
        .extracting(value -> value.conditionId())
        .containsExactly(201L, 202L);
  }

  @Test
  void termLongerThanPlanReturnsNumberlessFallback() {
    ProductRow product = product(1, "001", "A", null);
    when(savings.product(1)).thenReturn(Optional.of(product));
    when(savings.simpleOption(1, 101)).thenReturn(Optional.of(option(101, 1, 24, "3.0")));
    when(savings.selectedRuleConditions(1, List.of())).thenReturn(List.of());

    var response = service.whatIf(7, 1, new WhatIfRequest(101L, List.of()));

    assertThat(response.calculable()).isFalse();
    assertThat(response.message()).doesNotContainPattern("\\d");
    assertThat(response.termMonths()).isNull();
    assertThat(response.appliedRate()).isNull();
    assertThat(response.monthlySavings()).isNull();
    assertThat(response.pretaxInterest()).isNull();
    assertThat(response.acceleratedMonths()).isNull();
  }

  @Test
  void rejectsConditionIdsThatAreNotCurrentRulesForTheProduct() {
    when(savings.product(1)).thenReturn(Optional.of(product(1, "001", "A", null)));
    when(savings.simpleOption(1, 101)).thenReturn(Optional.of(option(101, 1, 12, "3.0")));
    when(savings.selectedRuleConditions(1, List.of(999L))).thenReturn(List.of());

    assertThatThrownBy(() -> service.whatIf(7, 1, new WhatIfRequest(101L, List.of(999L))))
        .isInstanceOf(ApiException.class)
        .extracting(exception -> ((ApiException) exception).code())
        .isEqualTo("INVALID_SAVINGS_CONDITION");
  }

  private static ProductRow product(long id, String company, String code, Long limit) {
    return new ProductRow(id, company, code, "은행", "상품", "1", limit);
  }

  private static OptionRow option(long id, long productId, int term, String rate) {
    return new OptionRow(id, productId, "F", "정액적립식", term, rate(rate), rate(rate));
  }

  private static BigDecimal rate(String value) {
    return new BigDecimal(value);
  }
}

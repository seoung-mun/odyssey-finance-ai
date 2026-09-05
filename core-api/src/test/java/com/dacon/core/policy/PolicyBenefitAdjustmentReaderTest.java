package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dacon.core.plan.FutureCashflowAdjustment;
import com.dacon.core.policy.PolicyBenefitDtos.PolicyBenefitResponse;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyBenefitAdjustmentReaderTest {
  private final PolicyBenefitService benefits = mock(PolicyBenefitService.class);
  private final PolicyBenefitAdjustmentReader reader = new PolicyBenefitAdjustmentReader(benefits);

  @Test
  void noConfirmedBenefitsProducesImmutableEmptyList() {
    when(benefits.list(3, 9)).thenReturn(List.of());

    List<FutureCashflowAdjustment> result = reader.read(3, 9);

    assertThat(result).isEmpty();
    assertThatThrownBy(
            () ->
                result.add(
                    new FutureCashflowAdjustment(
                        "POLICY_BENEFIT",
                        1,
                        2,
                        "ONE_TIME_FUNDING",
                        1,
                        YearMonth.of(2026, 10),
                        null,
                        Instant.EPOCH)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void mapsEachBenefitWithoutMergingAndOrdersByStartMonthThenId() {
    Instant confirmed = Instant.parse("2026-09-05T01:02:03Z");
    when(benefits.list(3, 9))
        .thenReturn(
            List.of(
                benefit(30, 103, "ONE_TIME_FUNDING", 300_000, "2026-11", null, confirmed),
                benefit(
                    20, 102, "MONTHLY_EXPENSE_REDUCTION", 200_000, "2026-10", "2027-09", confirmed),
                benefit(10, 101, "ONE_TIME_FUNDING", 100_000, "2026-10", null, confirmed)));

    List<FutureCashflowAdjustment> result = reader.read(3, 9);

    assertThat(result)
        .extracting(FutureCashflowAdjustment::policyBenefitId)
        .containsExactly(10L, 20L, 30L);
    assertThat(result.get(0))
        .isEqualTo(
            new FutureCashflowAdjustment(
                "POLICY_BENEFIT",
                10,
                101,
                "ONE_TIME_FUNDING",
                100_000,
                YearMonth.of(2026, 10),
                null,
                confirmed));
    assertThat(result.get(1).adjustmentType()).isEqualTo("MONTHLY_EXPENSE_REDUCTION");
    assertThat(result.get(1).amountWon()).isEqualTo(200_000);
    assertThat(result.get(1).endYearMonth()).isEqualTo(YearMonth.of(2027, 9));
  }

  @Test
  void impossibleServiceStateFailsClosed() {
    when(benefits.list(3, 9))
        .thenReturn(
            List.of(
                new PolicyBenefitResponse(
                    1,
                    9,
                    2,
                    "ONE_TIME_FUNDING",
                    300_000,
                    YearMonth.of(2026, 10),
                    null,
                    "CANCELLED",
                    Instant.EPOCH)));

    assertThatThrownBy(() -> reader.read(3, 9)).isInstanceOf(IllegalStateException.class);
  }

  private PolicyBenefitResponse benefit(
      long id,
      long versionId,
      String type,
      long amount,
      String start,
      String end,
      Instant confirmedAt) {
    return new PolicyBenefitResponse(
        id,
        9,
        versionId,
        type,
        amount,
        YearMonth.parse(start),
        end == null ? null : YearMonth.parse(end),
        "CONFIRMED",
        confirmedAt);
  }
}

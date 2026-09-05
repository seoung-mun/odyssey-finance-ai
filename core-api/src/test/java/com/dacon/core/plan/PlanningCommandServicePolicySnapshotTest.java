package com.dacon.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.goal.FinancialGoalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class PlanningCommandServicePolicySnapshotTest {
  @Test
  void savedPlanKeepsAnIndependentSnapshotOfConfirmedBenefitProvenance() {
    FinancialGoalRepository goals = mock(FinancialGoalRepository.class);
    PlanVersionRepository plans = mock(PlanVersionRepository.class);
    PlanningQueryService queries = mock(PlanningQueryService.class);
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    FinancialGoal goal = mock(FinancialGoal.class);
    FutureCashflowAdjustment adjustment =
        new FutureCashflowAdjustment(
            "POLICY_BENEFIT",
            31,
            41,
            "MONTHLY_EXPENSE_REDUCTION",
            200_000,
            YearMonth.of(2026, 10),
            YearMonth.of(2027, 9),
            Instant.parse("2026-09-05T01:02:03Z"));
    PlanInput input = input(mapper, adjustment);

    when(queries.readPlanInputForUpdate(3, 9)).thenReturn(input);
    when(plans.findByGoalIdAndStatusOrderByVersionNoDesc(9, "PROPOSED")).thenReturn(List.of());
    when(goals.findByIdAndUserId(9, 3)).thenReturn(Optional.of(goal));
    when(plans.findMaxVersionNo(9)).thenReturn(0);
    when(plans.saveAndFlush(any(PlanVersion.class)))
        .thenAnswer(
            invocation -> {
              PlanVersion plan = invocation.getArgument(0);
              ReflectionTestUtils.setField(plan, "id", 11);
              return plan;
            });
    ArgumentCaptor<PlanVersion> savedPlan = ArgumentCaptor.forClass(PlanVersion.class);

    PlanningCommandService service =
        new PlanningCommandService(
            goals,
            plans,
            mock(SimulationRunRepository.class),
            mock(PlanOptionRepository.class),
            mock(PlanBandRepository.class),
            mock(ReplanEventRepository.class),
            queries,
            mapper);

    service.save(3, 9, input, null, "INITIAL", "infeasible");

    org.mockito.Mockito.verify(plans).saveAndFlush(savedPlan.capture());
    PlanVersion saved = savedPlan.getValue();
    assertThat(saved.policySnapshot().path("futureCashflowAdjustments")).hasSize(1);
    assertThat(saved.policySnapshot().at("/futureCashflowAdjustments/0/policyBenefitId").asLong())
        .isEqualTo(31);
    assertThat(saved.policySnapshot().at("/futureCashflowAdjustments/0/policyVersionId").asLong())
        .isEqualTo(41);
    assertThat(saved.policySnapshot().at("/futureCashflowAdjustments/0/amountWon").asLong())
        .isEqualTo(200_000);
    assertThat(saved.policySnapshot().at("/futureCashflowAdjustments/0/startYearMonth").asText())
        .isEqualTo("2026-10");
    assertThat(saved.policySnapshot().at("/futureCashflowAdjustments/0/endYearMonth").asText())
        .isEqualTo("2027-09");
    assertThat(input.policySnapshot().has("futureCashflowAdjustments")).isFalse();
  }

  private PlanInput input(ObjectMapper mapper, FutureCashflowAdjustment adjustment) {
    return new PlanInput(
        3,
        9,
        "내 집",
        100_000_000,
        10_000_000,
        LocalDate.of(2027, 9, 1),
        3_000_000,
        1_000_000,
        List.of(1L, 2L, 3L),
        List.of(),
        12,
        List.of(1.0),
        10_000_000,
        0,
        1_000_000,
        mapper.createObjectNode().put("aggressiveWarningPct", 0.2),
        true,
        "none",
        List.of(adjustment));
  }
}

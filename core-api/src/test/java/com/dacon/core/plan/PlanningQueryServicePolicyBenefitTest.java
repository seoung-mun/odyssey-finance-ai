package com.dacon.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.goal.FinancialGoalRepository;
import com.dacon.core.goal.ScheduledExpenseRepository;
import com.dacon.core.policy.PolicyBenefitAdjustmentReader;
import com.dacon.core.transaction.TransactionRepository;
import com.dacon.core.user.entity.FinancialProfile;
import com.dacon.core.user.repository.FinancialProfileRepository;
import com.dacon.core.user.repository.UserProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlanningQueryServicePolicyBenefitTest {
  @Test
  void connectsConfirmedAdjustmentsWithoutChangingFinancialState() {
    FinancialGoalRepository goals = mock(FinancialGoalRepository.class);
    FinancialProfileRepository financialProfiles = mock(FinancialProfileRepository.class);
    UserProfileRepository userProfiles = mock(UserProfileRepository.class);
    ScheduledExpenseRepository scheduledExpenses = mock(ScheduledExpenseRepository.class);
    TransactionRepository transactions = mock(TransactionRepository.class);
    PlanVersionRepository plans = mock(PlanVersionRepository.class);
    PolicyBenefitAdjustmentReader benefits = mock(PolicyBenefitAdjustmentReader.class);
    FinancialGoal goal = mock(FinancialGoal.class);
    FinancialProfile profile = mock(FinancialProfile.class);
    FutureCashflowAdjustment adjustment =
        new FutureCashflowAdjustment(
            "POLICY_BENEFIT",
            31,
            41,
            "ONE_TIME_FUNDING",
            300_000,
            YearMonth.now(PlanningQueryService.KST).plusMonths(1),
            null,
            Instant.parse("2026-09-05T01:02:03Z"));

    when(goals.findByIdAndUserId(9, 3)).thenReturn(Optional.of(goal));
    when(goal.id()).thenReturn(9);
    when(goal.userId()).thenReturn(3);
    when(goal.status()).thenReturn("ACTIVE");
    when(goal.name()).thenReturn("내 집");
    when(goal.targetAmount()).thenReturn(100_000_000L);
    when(goal.currentSavedAmount()).thenReturn(10_000_000L);
    when(goal.targetDate()).thenReturn(LocalDate.now(PlanningQueryService.KST).plusMonths(2));
    when(financialProfiles.findById(3)).thenReturn(Optional.of(profile));
    when(profile.monthlyIncome()).thenReturn(3_000_000L);
    when(profile.monthlyFixedCost()).thenReturn(1_000_000L);
    when(transactions.monthlySpendingWindow(anyInt(), any(), any())).thenReturn(List.of());
    when(scheduledExpenses.findByUserIdAndStatusAndScheduledDateBetweenOrderByScheduledDateAscIdAsc(
            anyInt(), anyString(), any(), any()))
        .thenReturn(List.of());
    when(transactions.sumNetAmount(anyInt(), any(), any())).thenReturn(0L);
    when(plans.findByGoalIdOrderByVersionNoDesc(9)).thenReturn(List.of());
    when(userProfiles.existsById(3)).thenReturn(true);
    when(benefits.read(3, 9)).thenReturn(List.of(adjustment));

    PlanningQueryService service =
        new PlanningQueryService(
            goals,
            financialProfiles,
            userProfiles,
            scheduledExpenses,
            transactions,
            plans,
            mock(SimulationRunRepository.class),
            mock(PlanOptionRepository.class),
            mock(ReplanEventRepository.class),
            benefits,
            mock(PlanningMapper.class),
            new ObjectMapper());

    PlanInput input = service.readPlanInput(3, 9);

    assertThat(input.currentSavedAmount()).isEqualTo(10_000_000L);
    assertThat(input.monthlyIncome()).isEqualTo(3_000_000L);
    assertThat(input.monthlyFixedCost()).isEqualTo(1_000_000L);
    assertThat(input.futureCashflowAdjustments()).containsExactly(adjustment);
  }
}

package com.dacon.core.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dacon.core.auth.UserAccountRepository;
import com.dacon.core.error.ApiException;
import com.dacon.core.goal.dto.GoalDtos.ScheduledExpenseInput;
import com.dacon.core.goal.dto.GoalDtos.ScheduledExpensePatch;
import com.dacon.core.plan.PlanInput;
import com.dacon.core.plan.PlanPreview;
import com.dacon.core.plan.PlanVersionRepository;
import com.dacon.core.plan.PlanningCommandService;
import com.dacon.core.plan.PlanningQueryService;
import com.dacon.core.plan.PlanningServiceImpl;
import com.dacon.core.plan.ReplanEventRepository;
import com.dacon.core.plan.SavedPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScheduledExpenseReplanServiceTest {
  @Test
  void updateKeepsExistingIdOrderForSameDate() {
    ScheduledExpenseRepository expenses = mock(ScheduledExpenseRepository.class);
    FinancialGoal goal =
        new FinancialGoal(
            new com.dacon.core.auth.UserAccount(), "목표", 1_000L, 0, LocalDate.of(2027, 12, 31));
    LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    LocalDate sameDate = today.plusMonths(2);
    ScheduledExpense first =
        new ScheduledExpense(new com.dacon.core.auth.UserAccount(), "첫째", 100L, sameDate);
    ScheduledExpense last =
        new ScheduledExpense(new com.dacon.core.auth.UserAccount(), "둘째", 200L, sameDate);
    org.springframework.test.util.ReflectionTestUtils.setField(first, "id", 5);
    org.springframework.test.util.ReflectionTestUtils.setField(last, "id", 6);
    when(expenses.findByUserIdAndStatusAndScheduledDateBetweenOrderByScheduledDateAscIdAsc(
            7, "PLANNED", today, goal.targetDate()))
        .thenReturn(List.of(first, last));
    ScheduledExpenseReplanService service =
        new ScheduledExpenseReplanService(
            mock(FinancialGoalRepository.class),
            expenses,
            mock(PlanVersionRepository.class),
            mock(PlanningQueryService.class),
            mock(PlanningServiceImpl.class),
            mock(ScheduledExpenseReplanService.Command.class));

    @SuppressWarnings("unchecked")
    List<PlanInput.ScheduledInput> proposed =
        (List<PlanInput.ScheduledInput>)
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "scheduled", 7, goal, 5, 150L, sameDate, "PLANNED");

    assertThat(proposed)
        .containsExactly(
            new PlanInput.ScheduledInput(3, 150L), new PlanInput.ScheduledInput(3, 200L));
  }

  @Test
  void createSortsProposedExpensesLikePersistedSnapshot() {
    FinancialGoalRepository goals = mock(FinancialGoalRepository.class);
    ScheduledExpenseRepository expenses = mock(ScheduledExpenseRepository.class);
    PlanVersionRepository plans = mock(PlanVersionRepository.class);
    PlanningQueryService queries = mock(PlanningQueryService.class);
    PlanningServiceImpl planning = mock(PlanningServiceImpl.class);
    ScheduledExpenseReplanService.Command command =
        mock(ScheduledExpenseReplanService.Command.class);
    FinancialGoal goal =
        new FinancialGoal(
            new com.dacon.core.auth.UserAccount(), "목표", 1_000L, 0, LocalDate.of(2026, 12, 31));
    org.springframework.test.util.ReflectionTestUtils.setField(goal, "id", 9);
    LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    ScheduledExpense existing =
        new ScheduledExpense(
            new com.dacon.core.auth.UserAccount(), "나중", 100L, today.plusMonths(2));

    when(goals.findFirstByUserIdAndStatus(7, "ACTIVE")).thenReturn(java.util.Optional.of(goal));
    when(plans.findFirstByGoalIdAndStatusOrderByVersionNoDesc(9, "ACTIVE"))
        .thenReturn(java.util.Optional.of(mock(com.dacon.core.plan.PlanVersion.class)));
    when(expenses.findByUserIdAndStatusAndScheduledDateBetweenOrderByScheduledDateAscIdAsc(
            7, "PLANNED", today, goal.targetDate()))
        .thenReturn(List.of(existing));
    PlanInput original = input(100L);
    when(queries.readPlanInput(7, 9)).thenReturn(original);
    when(queries.readPlanInputWithScheduled(
            org.mockito.ArgumentMatchers.eq(7),
            org.mockito.ArgumentMatchers.eq(9),
            org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(input(300L));
    when(planning.preview(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(
            new PlanPreview(input(300L), new ObjectMapper().createObjectNode(), null, null));
    when(command.create(
            org.mockito.ArgumentMatchers.eq(7),
            org.mockito.ArgumentMatchers.eq(9),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(original),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new ScheduledExpenseReplanService.Result(existing, 1, new SavedPlan(2, "hash")));

    new ScheduledExpenseReplanService(goals, expenses, plans, queries, planning, command)
        .create(7, new ScheduledExpenseInput("먼저", 200L, today.plusMonths(1)), "request");

    ArgumentCaptor<List<PlanInput.ScheduledInput>> captured = ArgumentCaptor.forClass(List.class);
    verify(queries)
        .readPlanInputWithScheduled(
            org.mockito.ArgumentMatchers.eq(7),
            org.mockito.ArgumentMatchers.eq(9),
            captured.capture());
    assertThat(captured.getValue())
        .containsExactly(
            new PlanInput.ScheduledInput(2, 200L), new PlanInput.ScheduledInput(3, 100L));
  }

  @Test
  void staleScheduledPatchStopsBeforeExpenseMutation() {
    ScheduledExpenseRepository expenses = mock(ScheduledExpenseRepository.class);
    PlanningQueryService queries = mock(PlanningQueryService.class);
    PlanInput original = input(100L);
    when(queries.readPlanInputForUpdate(7, 9)).thenReturn(input(200L));
    ScheduledExpenseReplanService.Command command =
        new ScheduledExpenseReplanService.Command(
            expenses,
            mock(FinancialGoalRepository.class),
            mock(PlanVersionRepository.class),
            mock(ReplanEventRepository.class),
            mock(UserAccountRepository.class),
            queries,
            mock(PlanningCommandService.class),
            new ObjectMapper());

    assertThatThrownBy(
            () ->
                command.update(
                    7,
                    9,
                    11,
                    new ScheduledExpensePatch(null, 200L, null, null),
                    original,
                    input(200L),
                    null))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("PLAN_INPUT_CHANGED");

    verify(expenses, never()).findByIdAndUserId(11, 7);
  }

  private PlanInput input(long scheduledAmount) {
    return new PlanInput(
        7,
        9,
        "목표",
        1_000L,
        0,
        LocalDate.of(2026, 9, 1),
        3_000L,
        1_000L,
        "OFF",
        null,
        List.of(100L, 200L, 300L),
        List.of(new PlanInput.ScheduledInput(1, scheduledAmount)),
        1,
        List.of(1.0),
        1_000L - scheduledAmount,
        0,
        200L,
        new ObjectMapper().createObjectNode(),
        true,
        "1:");
  }
}

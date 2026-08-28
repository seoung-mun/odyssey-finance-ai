package com.dacon.core.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dacon.core.auth.UserAccount;
import com.dacon.core.auth.UserAccountRepository;
import com.dacon.core.error.ApiException;
import com.dacon.core.goal.dto.GoalDtos.GoalPatch;
import com.dacon.core.goal.dto.GoalDtos.GoalRequest;
import com.dacon.core.goal.dto.GoalDtos.ScheduledExpenseInput;
import com.dacon.core.goal.dto.GoalDtos.ScheduledExpensePatch;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GoalServiceImplTest {
  @Test
  void getUsesAuthenticatedOwner() {
    FinancialGoalRepository goals = mock(FinancialGoalRepository.class);
    GoalService service =
        new GoalServiceImpl(
            mock(UserAccountRepository.class), goals, mock(ScheduledExpenseRepository.class));
    when(goals.findByIdAndUserId(9, 7)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.goal(7, 9)).isInstanceOf(ApiException.class);
    verify(goals).findByIdAndUserId(9, 7);
  }

  @Test
  void createRejectsGoalDateThatIsNotFuture() {
    GoalService service =
        new GoalServiceImpl(
            mock(UserAccountRepository.class),
            mock(FinancialGoalRepository.class),
            mock(ScheduledExpenseRepository.class));

    assertThatThrownBy(
            () ->
                service.create(
                    7, new GoalRequest("비상금", 1_000_000L, 0L, LocalDate.now(GoalServiceImpl.KST))))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void updateChangesOnlyProvidedGoalFieldsForOwner() {
    FinancialGoalRepository goals = mock(FinancialGoalRepository.class);
    FinancialGoal goal = goal(9, "기존", 1_000_000L, 100_000L, "ACTIVE");
    when(goals.findByIdAndUserId(9, 7)).thenReturn(Optional.of(goal));
    GoalService service =
        new GoalServiceImpl(
            mock(UserAccountRepository.class), goals, mock(ScheduledExpenseRepository.class));

    var response =
        service.updateGoal(
            7,
            9,
            new GoalPatch(
                " 수정 ", 2_000_000L, null, LocalDate.now(GoalServiceImpl.KST).plusDays(1), null));

    assertThat(response.name()).isEqualTo("수정");
    assertThat(response.targetAmount()).isEqualTo(2_000_000L);
    assertThat(response.currentSavedAmount()).isEqualTo(100_000L);
    assertThat(response.targetDate()).isEqualTo(LocalDate.now(GoalServiceImpl.KST).plusDays(1));
    assertThat(response.triggeredReplanEventId()).isNull();
  }

  @Test
  void createScheduledExpenseReturnsPlannedExpense() {
    UserAccountRepository users = mock(UserAccountRepository.class);
    ScheduledExpenseRepository expenses = mock(ScheduledExpenseRepository.class);
    UserAccount user = new UserAccount();
    ReflectionTestUtils.setField(user, "id", 7);
    when(users.findById(7)).thenReturn(Optional.of(user));
    when(expenses.saveAndFlush(org.mockito.ArgumentMatchers.any(ScheduledExpense.class)))
        .thenAnswer(
            invocation -> {
              ScheduledExpense expense = invocation.getArgument(0);
              ReflectionTestUtils.setField(expense, "id", 11);
              return expense;
            });
    GoalService service = new GoalServiceImpl(users, mock(FinancialGoalRepository.class), expenses);

    var response =
        service.createScheduledExpense(
            7, new ScheduledExpenseInput("여행", 500_000L, LocalDate.of(2026, 9, 1)));

    assertThat(response.id()).isEqualTo(11);
    assertThat(response.name()).isEqualTo("여행");
    assertThat(response.amount()).isEqualTo(500_000L);
    assertThat(response.scheduledDate()).isEqualTo(LocalDate.of(2026, 9, 1));
    assertThat(response.status()).isEqualTo("PLANNED");
    assertThat(response.matchedTransactionCount()).isZero();
    assertThat(response.matchedAmount()).isZero();
    assertThat(response.triggeredReplanEventId()).isNull();
  }

  @Test
  void updateScheduledExpenseRejectsOtherUsersExpense() {
    ScheduledExpenseRepository expenses = mock(ScheduledExpenseRepository.class);
    when(expenses.findByIdAndUserId(12, 7)).thenReturn(Optional.empty());
    GoalService service =
        new GoalServiceImpl(
            mock(UserAccountRepository.class), mock(FinancialGoalRepository.class), expenses);

    assertThatThrownBy(
            () ->
                service.updateScheduledExpense(
                    7, 12, new ScheduledExpensePatch(null, null, null, "CANCELLED")))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("RESOURCE_NOT_FOUND");
  }

  @Test
  void suppressSpendingReplanUntilStoresGivenDate() {
    FinancialGoal goal = goal(9, "기존", 1_000_000L, 100_000L, "ACTIVE");
    LocalDate until = LocalDate.of(2026, 9, 30);

    goal.suppressSpendingReplanUntil(until);

    assertThat(goal.spendingReplanSuppressedUntil()).isEqualTo(until);
  }

  private FinancialGoal goal(
      int id, String name, long targetAmount, long currentSavedAmount, String status) {
    FinancialGoal goal =
        new FinancialGoal(
            new UserAccount(),
            name,
            targetAmount,
            currentSavedAmount,
            LocalDate.now(GoalServiceImpl.KST).plusDays(1));
    ReflectionTestUtils.setField(goal, "id", id);
    ReflectionTestUtils.setField(goal, "status", status);
    return goal;
  }
}

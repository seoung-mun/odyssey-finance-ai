package com.dacon.core.goal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dacon.core.auth.UserAccountRepository;
import com.dacon.core.error.ApiException;
import com.dacon.core.goal.dto.GoalDtos.GoalRequest;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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
}

package com.dacon.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dacon.core.goal.FinancialGoal;
import org.junit.jupiter.api.Test;

class ReplanRetryServiceTest {
  @Test
  void retryGoalIdIsResolvedInsideEventCommandTransaction() {
    ReplanEventRepository events = mock(ReplanEventRepository.class);
    ReplanEvent event = mock(ReplanEvent.class);
    PlanVersion source = mock(PlanVersion.class);
    FinancialGoal goal = mock(FinancialGoal.class);
    when(events.findByIdAndUserId(12, 7)).thenReturn(java.util.Optional.of(event));
    when(event.sourcePlanVersion()).thenReturn(source);
    when(source.goal()).thenReturn(goal);
    when(goal.id()).thenReturn(9);
    ReplanEventCommand command =
        new ReplanEventCommand(
            events,
            mock(com.dacon.core.goal.FinancialGoalRepository.class),
            mock(PlanVersionRepository.class),
            mock(com.dacon.core.auth.UserAccountRepository.class),
            mock(com.dacon.core.transaction.TransactionRepository.class),
            mock(PlanningQueryService.class),
            mock(PlanningCommandService.class));

    assertThat(command.retryGoalId(7, 12)).isEqualTo(9);
  }
}

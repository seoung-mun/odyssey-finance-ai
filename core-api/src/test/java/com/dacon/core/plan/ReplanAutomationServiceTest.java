package com.dacon.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.goal.FinancialGoalRepository;
import com.dacon.core.transaction.Transaction;
import com.dacon.core.transaction.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class ReplanAutomationServiceTest {
  @Test
  void shockStopsWhenSelectedOptionIsMissing() {
    TransactionRepository transactions = mock(TransactionRepository.class);
    FinancialGoalRepository goals = mock(FinancialGoalRepository.class);
    PlanVersionRepository plans = mock(PlanVersionRepository.class);
    PlanOptionRepository options = mock(PlanOptionRepository.class);
    FinancialGoal goal = mock(FinancialGoal.class);
    Transaction payment = mock(Transaction.class);
    PlanVersion active = mock(PlanVersion.class);
    when(goal.id()).thenReturn(9);
    when(payment.transactionType()).thenReturn("PAYMENT");
    when(transactions.findByIdAndUserId(12, 7)).thenReturn(Optional.of(payment));
    when(plans.findFirstByGoalIdAndStatusOrderByVersionNoDesc(9, "ACTIVE"))
        .thenReturn(Optional.of(active));
    when(active.id()).thenReturn(11);
    when(options.findByPlanVersionIdAndSelectedAtIsNotNull(11)).thenReturn(Optional.empty());
    ReplanAutomationService service =
        new ReplanAutomationService(
            transactions,
            goals,
            plans,
            options,
            mock(ReplanEventRepository.class),
            mock(ReplanService.class),
            new ObjectMapper());

    service.evaluateShock(7, goal, 12);

    verify(transactions, never()).findPreviousVariablePaymentAmounts(7, 12);
  }

  @Test
  void keepsMonthlyCronAndAddsFixedCheckpointCron() throws Exception {
    assertThat(
            ReplanAutomationService.class
                .getDeclaredMethod("monthly")
                .getAnnotation(Scheduled.class)
                .cron())
        .isEqualTo("0 0 3 1 * *");
    assertThat(
            ReplanAutomationService.class
                .getDeclaredMethod("checkpoints")
                .getAnnotation(Scheduled.class)
                .cron())
        .isEqualTo("0 0 3 7,14,21 * *");
  }
}

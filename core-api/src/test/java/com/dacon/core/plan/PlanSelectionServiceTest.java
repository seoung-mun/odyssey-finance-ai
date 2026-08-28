package com.dacon.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dacon.core.analysis.AnalysisServicePort;
import com.dacon.core.explanation.ExplanationQueuePort;
import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.goal.FinancialGoalRepository;
import com.dacon.core.plan.dto.PlanningDtos.PlanDetailResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PlanSelectionServiceTest {
  @Test
  void supersededPlanIsFlushedBeforeProposedPlanBecomesActive() {
    FinancialGoalRepository goals = mock(FinancialGoalRepository.class);
    PlanVersionRepository plans = mock(PlanVersionRepository.class);
    PlanOptionRepository options = mock(PlanOptionRepository.class);
    PlanVersion proposed = mock(PlanVersion.class);
    PlanVersion active = mock(PlanVersion.class);
    PlanOption option = mock(PlanOption.class);
    FinancialGoal goal = mock(FinancialGoal.class);
    when(plans.findOwnedForUpdate(7, 11)).thenReturn(java.util.Optional.of(proposed));
    when(options.findForUpdate(11, 13)).thenReturn(java.util.Optional.of(option));
    when(proposed.goal()).thenReturn(goal);
    when(goal.id()).thenReturn(9);
    when(proposed.status()).thenReturn("PROPOSED");
    when(plans.findFirstByGoalIdAndStatusOrderByVersionNoDesc(9, "ACTIVE"))
        .thenReturn(java.util.Optional.of(active));
    PlanningCommandService command =
        new PlanningCommandService(
            goals,
            plans,
            mock(SimulationRunRepository.class),
            options,
            mock(PlanBandRepository.class),
            mock(PlanningQueryService.class),
            new ObjectMapper());

    command.select(7, 11, 13);

    org.mockito.InOrder order = inOrder(active, plans, proposed);
    order.verify(active).supersede();
    order.verify(plans).flush();
    order.verify(proposed).activate(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void selectingOptionReturnsActivatedPlanFromCommandTransaction() {
    PlanningQueryService queries = mock(PlanningQueryService.class);
    PlanningCommandService commands = mock(PlanningCommandService.class);
    PlanDetailResponse active = mock(PlanDetailResponse.class);
    when(commands.select(7, 11, 13)).thenReturn(11);
    when(queries.plan(7, 11)).thenReturn(active);
    PlanningService service =
        new PlanningServiceImpl(
            queries,
            commands,
            mock(AnalysisServicePort.class),
            mock(ExplanationQueuePort.class),
            new ObjectMapper());

    assertThat(service.select(7, 11, 13)).isSameAs(active);
  }
}

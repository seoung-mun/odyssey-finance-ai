package com.dacon.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dacon.core.analysis.AnalysisServicePort;
import com.dacon.core.explanation.ExplanationQueuePort;
import com.dacon.core.plan.dto.PlanningDtos.PlanDetailResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PlanSelectionServiceTest {
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

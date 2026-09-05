package com.dacon.core.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dacon.core.plan.PlanVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExplanationStateServiceTest {
  @Test
  void typedResultIsStoredWithDatabaseSafeDefaults() throws Exception {
    ExplanationJobRepository jobs = mock(ExplanationJobRepository.class);
    PlanVersion plan = mock(PlanVersion.class);
    ObjectMapper mapper = new ObjectMapper();
    ExplanationStateService service = new ExplanationStateService(jobs, mapper);
    when(jobs.findForUpdate(7L)).thenReturn(Optional.of(plan));
    when(plan.explanationStatus()).thenReturn("PROCESSING");
    ExplanationResult response =
        new ExplanationResult(
            ExplanationResult.Status.READY, "설명", null, 0, List.of(), Instant.now());
    ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> failedNumbers =
        ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
    ArgumentCaptor<Instant> generatedAt = ArgumentCaptor.forClass(Instant.class);

    assertThat(service.complete(7L, response)).isTrue();

    verify(plan)
        .completeExplanation(
            org.mockito.ArgumentMatchers.eq("READY"),
            org.mockito.ArgumentMatchers.eq("설명"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(0),
            failedNumbers.capture(),
            generatedAt.capture());
    assertThat(failedNumbers.getValue().isArray()).isTrue();
    assertThat(failedNumbers.getValue()).isEmpty();
    assertThat(generatedAt.getValue()).isNotNull();
  }
}

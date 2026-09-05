package com.dacon.core.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dacon.core.error.ApiException;
import com.dacon.core.explanation.dto.ExplanationJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ExplanationWorkerTest {
  private static final String HASH = "a".repeat(64);
  private final ObjectMapper mapper = new ObjectMapper();
  private final ExplanationStateService states = mock(ExplanationStateService.class);
  private final ExplanationGeneratorPort generator = mock(ExplanationGeneratorPort.class);
  private final ExplanationWorker worker = new ExplanationWorker(states, generator, mapper);

  @Test
  void readyResponseIsPersistedAfterExactNominalOptionIsLoaded() throws Exception {
    ExplanationJob job = job("PENDING");
    ExplanationResult response = ready("설명", 1);
    when(states.load(7L)).thenReturn(job);
    when(states.markProcessing(7L)).thenReturn(true);
    when(generator.generate(any(ExplanationRequest.class))).thenReturn(response);
    when(states.complete(7L, response)).thenReturn(true);

    assertThat(worker.process(7L, HASH, "v1")).isTrue();
    verify(generator).generate(any(ExplanationRequest.class));
    verify(states).complete(7L, response);
  }

  @Test
  void terminalDuplicateIsAcknowledgedWithoutCallingFastApi() {
    when(states.load(7L)).thenReturn(job("READY"));

    assertThat(worker.process(7L, HASH, "v1")).isTrue();
    verify(generator, never()).generate(any(ExplanationRequest.class));
  }

  @Test
  void fastApiFailureBecomesFallbackAndCanBeAcknowledged() {
    when(states.load(7L)).thenReturn(job("PENDING"));
    when(states.markProcessing(7L)).thenReturn(true);
    when(generator.generate(any(ExplanationRequest.class)))
        .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LLM_UNAVAILABLE", "down"));
    when(states.fallback(7L)).thenReturn(true);

    assertThat(worker.process(7L, HASH, "v1")).isTrue();
    verify(states).fallback(7L);
  }

  @Test
  void unapprovedNumberBecomesFallbackAndCanBeAcknowledged() {
    ExplanationResult response = ready("월 999원을 쓰세요", 0);
    when(states.load(7L)).thenReturn(job("PENDING"));
    when(states.markProcessing(7L)).thenReturn(true);
    when(generator.generate(any(ExplanationRequest.class))).thenReturn(response);
    when(states.fallback(7L)).thenReturn(true);

    assertThat(worker.process(7L, HASH, "v1")).isTrue();

    verify(states).fallback(7L);
    verify(states, never()).complete(7L, response);
  }

  @Test
  void typedResultWithDefaultsIsPersisted() {
    ExplanationResult response = ready("설명", 0);
    when(states.load(7L)).thenReturn(job("PENDING"));
    when(states.markProcessing(7L)).thenReturn(true);
    when(generator.generate(any(ExplanationRequest.class))).thenReturn(response);
    when(states.complete(7L, response)).thenReturn(true);

    assertThat(worker.process(7L, HASH, "v1")).isTrue();
    verify(states).complete(7L, response);
  }

  @Test
  void fallbackTextContainingAnyNumberIsRejected() {
    ExplanationResult response =
        new ExplanationResult(
            ExplanationResult.Status.FALLBACK,
            "800000원을 기준으로 확인해 주세요",
            null,
            0,
            List.of(),
            Instant.now());
    when(states.load(7L)).thenReturn(job("PENDING"));
    when(states.markProcessing(7L)).thenReturn(true);
    when(generator.generate(any(ExplanationRequest.class))).thenReturn(response);
    when(states.fallback(7L)).thenReturn(true);

    assertThat(worker.process(7L, HASH, "v1")).isTrue();
    verify(states).fallback(7L);
    verify(states, never()).complete(7L, response);
  }

  @Test
  void fallbackWithModelOrFailuresIsRejected() {
    ExplanationResult response =
        new ExplanationResult(
            ExplanationResult.Status.FALLBACK,
            "잠시 후 다시 확인해 주세요",
            "qwen",
            0,
            List.of("3"),
            Instant.now());
    when(states.load(7L)).thenReturn(job("PENDING"));
    when(states.markProcessing(7L)).thenReturn(true);
    when(generator.generate(any(ExplanationRequest.class))).thenReturn(response);
    when(states.fallback(7L)).thenReturn(true);

    assertThat(worker.process(7L, HASH, "v1")).isTrue();
    verify(states).fallback(7L);
    verify(states, never()).complete(7L, response);
  }

  @Test
  void missingNominalJoinFallsBackButUnknownPlanIsTerminal() {
    when(states.load(7L)).thenReturn(null);
    when(states.fallback(7L)).thenReturn(true);
    when(states.load(8L)).thenReturn(null);
    when(states.fallback(8L)).thenReturn(false);

    assertThat(worker.process(7L, HASH, "v1")).isTrue();
    assertThat(worker.process(8L, HASH, "v1")).isTrue();
    verify(states).fallback(7L);
    verify(states).fallback(8L);
  }

  @Test
  void dbUpdateRaceLeavesMessagePendingUnlessAnotherWorkerFinished() throws Exception {
    ExplanationResult response = ready("설명", 0);
    when(states.load(7L)).thenReturn(job("PENDING"));
    when(states.markProcessing(7L)).thenReturn(true);
    when(generator.generate(any(ExplanationRequest.class))).thenReturn(response);
    when(states.complete(7L, response)).thenReturn(false);
    when(states.status(7L)).thenReturn("PROCESSING");

    assertThatIllegalStateException().isThrownBy(() -> worker.process(7L, HASH, "v1"));
  }

  @Test
  void eventMustMatchInputHashAndPromptVersion() {
    when(states.load(7L)).thenReturn(job("PENDING"));
    when(states.markProcessing(7L)).thenReturn(true);
    when(states.fallback(7L)).thenReturn(true);

    assertThat(worker.process(7L, "b".repeat(64), "v1")).isTrue();
    verify(states).fallback(7L);
    verify(generator, never()).generate(any(ExplanationRequest.class));
  }

  private ExplanationJob job(String status) {
    return new ExplanationJob(
        7L,
        status,
        HASH,
        "v1",
        800_000L,
        1_200_000L,
        6_000_000L,
        2_000_000L,
        LocalDate.of(2026, 8, 25),
        LocalDate.of(2027, 2, 25),
        new BigDecimal("0.8000"),
        false);
  }

  private ExplanationResult ready(String text, int retryCount) {
    return new ExplanationResult(
        ExplanationResult.Status.READY, text, "qwen", retryCount, List.of(), Instant.now());
  }
}

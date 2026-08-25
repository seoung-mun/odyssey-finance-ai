package com.dacon.core.explanation;

import com.dacon.core.explanation.dto.ExplanationJob;
import com.dacon.core.plan.PlanVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 설명 작업 snapshot 조회와 짧은 상태 변경 transaction을 담당한다. */
@Service
public class ExplanationStateService {
  private final ExplanationJobRepository jobs;
  private final ObjectMapper mapper;

  public ExplanationStateService(ExplanationJobRepository jobs, ObjectMapper mapper) {
    this.jobs = jobs;
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public ExplanationJob load(long planVersionId) {
    return jobs.findJob(planVersionId).map(this::job).orElse(null);
  }

  @Transactional
  public boolean markProcessing(long planVersionId) {
    PlanVersion plan = jobs.findForUpdate(planVersionId).orElse(null);
    return plan != null && plan.beginExplanation();
  }

  @Transactional
  public boolean complete(long planVersionId, JsonNode response) {
    PlanVersion plan = jobs.findForUpdate(planVersionId).orElse(null);
    if (plan == null || !"PROCESSING".equals(plan.explanationStatus())) {
      return false;
    }
    plan.completeExplanation(
        response.path("status").asText(),
        response.path("text").asText(),
        response.path("model").isTextual() ? response.path("model").asText() : null,
        response.path("retryCount").asInt(0),
        response.has("failedNumbers") ? response.path("failedNumbers") : mapper.createArrayNode(),
        response.has("generatedAt")
            ? Instant.parse(response.path("generatedAt").asText())
            : Instant.now());
    return true;
  }

  @Transactional
  public boolean fallback(long planVersionId) {
    PlanVersion plan = jobs.findForUpdate(planVersionId).orElse(null);
    if (plan == null
        || !("PENDING".equals(plan.explanationStatus())
            || "PROCESSING".equals(plan.explanationStatus()))) {
      return false;
    }
    plan.fallbackExplanation(
        ExplanationQueuePublisher.FALLBACK_TEXT, Instant.now(), mapper.createArrayNode());
    return true;
  }

  @Transactional(readOnly = true)
  public String status(long planVersionId) {
    return jobs.findById((int) planVersionId).map(PlanVersion::explanationStatus).orElse(null);
  }

  @Transactional(readOnly = true)
  public List<PendingExplanation> pending() {
    return jobs.findPending().stream()
        .map(
            value ->
                new PendingExplanation(
                    value.getPlanVersionId(), value.getInputHash(), value.getPromptVersion()))
        .toList();
  }

  private ExplanationJob job(ExplanationJobRepository.ExplanationJobView value) {
    return new ExplanationJob(
        value.getPlanVersionId(),
        value.getStatus(),
        value.getInputHash(),
        value.getPromptVersion(),
        value.getRecommendedMonthlySpending(),
        value.getCurrentAvgVariableSpending(),
        value.getTargetAmount(),
        value.getCurrentSavedAmount(),
        value.getAsOfDate(),
        value.getTargetDate(),
        value.getSimulationCoverage(),
        value.getAggressiveWarning());
  }

  /** 재기동 시 queue에 복구할 설명 식별자다. */
  public record PendingExplanation(long planVersionId, String inputHash, String promptVersion) {}
}

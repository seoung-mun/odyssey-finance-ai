package com.dacon.core.explanation;

import com.dacon.core.explanation.dto.ExplanationJob;
import com.dacon.core.plan.PlanVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설명 작업 스냅샷 조회와 계획 설명 상태 전이를 각각 짧은 DB 트랜잭션으로 수행한다.
 *
 * <p>상태 변경은 계획 행을 비관적 잠금으로 읽어 중복 worker의 전이를 직렬화한다. FastAPI와 Redis 호출은 이 서비스의 트랜잭션에 포함되지 않는다.
 */
@Service
public class ExplanationStateService {
  private final ExplanationJobRepository jobs;
  private final ObjectMapper mapper;

  /**
   * 설명 작업 저장소와 누락 필드 기본값 생성에 쓸 JSON 매퍼를 받는다.
   *
   * @param jobs 계획·시뮬레이션·기준 옵션 조회 저장소
   * @param mapper 빈 {@code failedNumbers} 배열 생성에 사용할 매퍼
   */
  public ExplanationStateService(ExplanationJobRepository jobs, ObjectMapper mapper) {
    this.jobs = jobs;
    this.mapper = mapper;
  }

  /**
   * 계획과 정확히 일치하는 시뮬레이션 및 0.800 PRESET 옵션을 설명 스냅샷으로 읽는다.
   *
   * @param planVersionId 설명 대상 계획 버전 식별자
   * @return 작업 스냅샷, 필요한 조인 결과가 없으면 {@code null}
   */
  @Transactional(readOnly = true)
  public ExplanationJob load(long planVersionId) {
    return jobs.findJob(planVersionId).map(this::job).orElse(null);
  }

  /**
   * 잠근 계획의 설명 상태를 {@code PENDING}에서 {@code PROCESSING}으로 전이한다.
   *
   * @param planVersionId 전이할 계획 버전 식별자
   * @return 행이 존재하고 전이가 수행됐으면 {@code true}, 아니면 {@code false}
   */
  @Transactional
  public boolean markProcessing(long planVersionId) {
    PlanVersion plan = jobs.findForUpdate(planVersionId).orElse(null);
    return plan != null && plan.beginExplanation();
  }

  /**
   * {@code PROCESSING} 계획에 검증이 끝난 타입화된 설명 결과를 저장한다.
   *
   * @param planVersionId 완료할 계획 버전 식별자
   * @param result worker가 검증한 최종 설명 결과
   * @return 행이 존재하고 현재 상태가 {@code PROCESSING}이면 {@code true}, 아니면 {@code false}
   */
  @Transactional
  public boolean complete(long planVersionId, ExplanationResult result) {
    PlanVersion plan = jobs.findForUpdate(planVersionId).orElse(null);
    if (plan == null || !"PROCESSING".equals(plan.explanationStatus())) {
      return false;
    }
    plan.completeExplanation(
        result.status().name(),
        result.text(),
        result.model(),
        result.retryCount(),
        mapper.valueToTree(result.failedNumbers()),
        result.generatedAt());
    return true;
  }

  /**
   * 처리 가능한 설명 작업을 숫자 없는 고정 문구의 {@code FALLBACK} 상태로 마감한다.
   *
   * @param planVersionId fallback할 계획 버전 식별자
   * @return 상태가 {@code PENDING} 또는 {@code PROCESSING}이라 전이했으면 {@code true}, 아니면 {@code false}
   */
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

  /**
   * 경합 처리 판단을 위해 현재 설명 상태만 조회한다.
   *
   * @param planVersionId 조회할 계획 버전 식별자
   * @return 저장된 설명 상태, 계획 행이 없으면 {@code null}
   */
  @Transactional(readOnly = true)
  public String status(long planVersionId) {
    return jobs.findById((int) planVersionId).map(PlanVersion::explanationStatus).orElse(null);
  }

  /**
   * 재기동 복구 대상인 {@code PENDING}/{@code PROCESSING} 작업 식별자를 조회한다.
   *
   * @return Redis에 다시 발행할 작업 목록
   */
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

  /**
   * 재기동 시 Redis Stream에 복구할 설명 작업의 최소 식별자 묶음이다.
   *
   * @param planVersionId 설명 대상 계획 버전 식별자
   * @param inputHash 저장된 계산 입력의 SHA-256 해시
   * @param promptVersion 설명 생성에 사용할 프롬프트 버전
   */
  public record PendingExplanation(long planVersionId, String inputHash, String promptVersion) {}
}

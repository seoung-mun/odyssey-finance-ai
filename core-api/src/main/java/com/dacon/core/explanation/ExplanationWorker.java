package com.dacon.core.explanation;

import com.dacon.core.explanation.dto.ExplanationJob;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Redis 작업 식별자로 DB의 확정 계획 스냅샷을 읽어 설명을 생성하고 최종 상태를 저장한다.
 *
 * <p>상태 전이 트랜잭션 사이에서 로컬 설명 포트를 호출하므로 원격 호출 동안 DB 잠금을 보유하지 않는다. 작업 식별자 불일치, 응답 계약 위반, 허용되지 않은 숫자 또는
 * 호출 실패는 모두 설명만 {@code FALLBACK}으로 낮춘다.
 */
@Component
class ExplanationWorker {
  private final ExplanationStateService states;
  private final ExplanationGeneratorPort generator;
  private final ObjectMapper mapper;

  ExplanationWorker(
      ExplanationStateService states, ExplanationGeneratorPort generator, ObjectMapper mapper) {
    this.states = states;
    this.generator = generator;
    this.mapper = mapper;
  }

  /**
   * 하나의 설명 작업을 중복 처리에 안전하게 최종 상태까지 진행한다.
   *
   * @param planVersionId DB에서 확정 스냅샷을 찾을 계획 버전 식별자
   * @param inputHash 발행 시점 계산 입력 해시
   * @param promptVersion 발행 시점 프롬프트 버전
   * @return 메시지를 ACK해도 되는 최종 상태이면 {@code true}, 상태 갱신을 재시도해야 하면 예외 발생
   * @throws IllegalStateException 상태 갱신 경합 후에도 계획이 최종 상태가 아닌 경우
   */
  boolean process(long planVersionId, String inputHash, String promptVersion) {
    ExplanationJob job = states.load(planVersionId);
    if (job == null) {
      states.fallback(planVersionId);
      return true;
    }
    if (terminal(job.status())) {
      return true;
    }
    if ("PENDING".equals(job.status()) && !states.markProcessing(planVersionId)) {
      return acknowledgeRace(planVersionId);
    }
    if (!"PENDING".equals(job.status()) && !"PROCESSING".equals(job.status())) {
      return true;
    }
    if (inputHash == null
        || promptVersion == null
        || !inputHash.equals(job.inputHash())
        || !promptVersion.equals(job.promptVersion())
        || job.recommendedMonthlySpending() == null
        || job.simulationCoverage() == null
        || job.aggressiveWarning() == null) {
      return fallback(planVersionId);
    }
    ExplanationRequest request;
    try {
      request = request(job, "explanation-" + planVersionId);
    } catch (JsonProcessingException exception) {
      return fallback(planVersionId);
    }
    ExplanationResult response;
    try {
      response = generator.generate(request);
    } catch (RuntimeException exception) {
      return fallback(planVersionId);
    }
    if (!valid(response, request.allowedNumbers())) {
      return fallback(planVersionId);
    }
    return states.complete(planVersionId, response) || acknowledgeRace(planVersionId);
  }

  /**
   * 타입화된 설명 결과의 상태·숫자가 저장 가능한 계약을 따르는지 확인한다.
   *
   * @param response 로컬 설명 포트 결과
   * @param allowedNumbers 확정 계획에서 유래한 허용 정수
   * @return 모든 구조 및 숫자 검사가 통과하면 {@code true}
   */
  private boolean valid(ExplanationResult response, Set<Long> allowedNumbers) {
    if (response == null || response.text().isBlank()) {
      return false;
    }
    if (response.status() == ExplanationResult.Status.FALLBACK) {
      return response.model() == null
          && response.failedNumbers().isEmpty()
          && AllowedNumberValidator.validate(response.text(), Set.of()).valid();
    }
    return response.failedNumbers().isEmpty()
        && AllowedNumberValidator.validate(response.text(), allowedNumbers).valid();
  }

  private ExplanationRequest request(ExplanationJob job, String requestId)
      throws JsonProcessingException {
    Set<Long> allowedNumbers = AllowedNumberValidator.allowedNumbers(job);
    ObjectNode root = mapper.createObjectNode();
    root.put("planVersionId", job.planVersionId());
    root.set("allowedNumbers", mapper.valueToTree(allowedNumbers));
    ObjectNode plan = root.putObject("plan");
    plan.put("recommendedMonthlySpending", job.recommendedMonthlySpending());
    plan.put("currentAvgVariableSpending", job.currentAvgVariableSpending());
    plan.put("remainingMonths", AllowedNumberValidator.remainingMonths(job));
    plan.put("targetAmount", job.targetAmount());
    plan.put("currentSavedAmount", job.currentSavedAmount());
    plan.put("simulationCoverage", job.simulationCoverage());
    plan.put("aggressiveWarning", job.aggressiveWarning());
    return new ExplanationRequest(
        "확정 계획 JSON만 근거로 한국어 설명을 한 문단으로 작성하세요. 허용 숫자 이외의 숫자는 쓰지 마세요. "
            + mapper.writeValueAsString(root),
        allowedNumbers,
        requestId);
  }

  private boolean fallback(long planVersionId) {
    return states.fallback(planVersionId) || acknowledgeRace(planVersionId);
  }

  /**
   * 다른 worker가 먼저 최종 상태를 저장한 경합이면 현재 메시지를 완료 처리한다.
   *
   * @param planVersionId 상태를 다시 확인할 계획 버전 식별자
   * @return 이미 최종 상태이면 항상 {@code true}
   * @throws IllegalStateException 최종 상태가 아니어서 메시지를 ACK하면 안 되는 경우
   */
  private boolean acknowledgeRace(long planVersionId) {
    if (terminal(states.status(planVersionId))) {
      return true;
    }
    throw new IllegalStateException("explanation state update lost");
  }

  private boolean terminal(String status) {
    return "READY".equals(status) || "FALLBACK".equals(status) || "FAILED".equals(status);
  }
}

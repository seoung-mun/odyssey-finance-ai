package com.dacon.core.explanation;

import com.dacon.core.analysis.AnalysisServicePort;
import com.dacon.core.explanation.dto.ExplanationJob;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Redis 작업 식별자로 DB의 확정 계획 스냅샷을 읽어 설명을 생성하고 최종 상태를 저장한다.
 *
 * <p>상태 전이 트랜잭션 사이에서 FastAPI를 호출하므로 원격 호출 동안 DB 잠금을 보유하지 않는다. 작업 식별자 불일치, 응답 계약 위반, 허용되지 않은 숫자 또는
 * 호출 실패는 모두 설명만 {@code FALLBACK}으로 낮춘다.
 */
@Component
class ExplanationWorker {
  private static final Pattern NUMBER = Pattern.compile("(?<![\\d])\\d[\\d,]*(?![\\d])");
  private final ExplanationStateService states;
  private final AnalysisServicePort analysis;
  private final ObjectMapper mapper;

  ExplanationWorker(
      ExplanationStateService states, AnalysisServicePort analysis, ObjectMapper mapper) {
    this.states = states;
    this.analysis = analysis;
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
    JsonNode response;
    try {
      response = analysis.generateExplanation(request(job), "explanation-" + planVersionId);
    } catch (JsonProcessingException | RuntimeException exception) {
      return fallback(planVersionId);
    }
    if (!valid(response, job)) {
      return fallback(planVersionId);
    }
    return states.complete(planVersionId, response) || acknowledgeRace(planVersionId);
  }

  /**
   * 설명 응답의 상태·선택 필드·생성 시각·숫자가 저장 가능한 계약을 따르는지 확인한다.
   *
   * @param response FastAPI 설명 응답
   * @param job 숫자 허용 목록의 근거가 되는 확정 계획 스냅샷
   * @return 모든 구조 및 숫자 검사가 통과하면 {@code true}
   */
  private boolean valid(JsonNode response, ExplanationJob job) {
    if (!response.isObject()
        || !("READY".equals(response.path("status").asText())
            || "FALLBACK".equals(response.path("status").asText()))
        || !response.path("text").isTextual()
        || response.path("text").asText().isBlank()
        || !optionalModel(response)
        || !optionalRetryCount(response)
        || !optionalStringArray(response, "failedNumbers")
        || !optionalText(response, "generatedAt")) {
      return false;
    }
    try {
      if (response.has("generatedAt")) {
        Instant.parse(response.path("generatedAt").asText());
      }
      String status = response.path("status").asText();
      if ("FALLBACK".equals(status)
          && (!(response.path("model").isMissingNode() || response.path("model").isNull())
              || NUMBER.matcher(response.path("text").asText()).find())) {
        return false;
      }
      if ("READY".equals(status)
          && response.has("failedNumbers")
          && !response.path("failedNumbers").isEmpty()) {
        return false;
      }
      return numbersAllowed(response.path("text").asText(), allowedNumbers(job));
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private boolean optionalModel(JsonNode response) {
    JsonNode value = response.path("model");
    return value.isMissingNode() || value.isTextual() || value.isNull();
  }

  private boolean optionalRetryCount(JsonNode response) {
    JsonNode value = response.path("retryCount");
    return value.isMissingNode()
        || (value.isIntegralNumber()
            && value.canConvertToInt()
            && value.asInt() >= 0
            && value.asInt() <= 2);
  }

  private boolean optionalStringArray(JsonNode response, String name) {
    JsonNode value = response.path(name);
    if (value.isMissingNode()) {
      return true;
    }
    if (!value.isArray()) {
      return false;
    }
    for (JsonNode item : value) {
      if (!item.isTextual()) {
        return false;
      }
    }
    return true;
  }

  private boolean optionalText(JsonNode response, String name) {
    JsonNode value = response.path(name);
    return value.isMissingNode() || value.isTextual();
  }

  /**
   * 모델 설명에 그대로 나타날 수 있는 정수 집합을 확정 스냅샷에서 만든다.
   *
   * @param job 설명 대상 계획 스냅샷
   * @return 권장 지출, 현재 평균, 잔여 개월, 목표액과 현재 저축액의 중복 없는 집합
   */
  private Set<Long> allowedNumbers(ExplanationJob job) {
    int remainingMonths =
        Math.max(
            1,
            (int)
                    ChronoUnit.MONTHS.between(
                        YearMonth.from(job.asOfDate()), YearMonth.from(job.targetDate()))
                + 1);
    Set<Long> allowed = new LinkedHashSet<>();
    allowed.add(job.recommendedMonthlySpending());
    allowed.add(job.currentAvgVariableSpending());
    allowed.add((long) remainingMonths);
    allowed.add(job.targetAmount());
    allowed.add(job.currentSavedAmount());
    return allowed;
  }

  /**
   * 설명에서 추출한 모든 정수가 허용 목록에 포함되는지 검사한다.
   *
   * @param text 검사할 자연어 설명
   * @param allowed 확정 JSON에서 유래한 허용 정수
   * @return 숫자가 없거나 모든 숫자가 허용되면 {@code true}
   */
  private boolean numbersAllowed(String text, Set<Long> allowed) {
    Matcher matcher = NUMBER.matcher(text);
    while (matcher.find()) {
      try {
        if (!allowed.contains(Long.parseLong(matcher.group().replace(",", "")))) {
          return false;
        }
      } catch (NumberFormatException exception) {
        return false;
      }
    }
    return true;
  }

  /**
   * 확정 계획 값과 동일한 허용 숫자 목록을 내부 설명 API 요청으로 직렬화한다.
   *
   * @param job DB에서 읽은 불변 계획 스냅샷
   * @return 내부 설명 API 요청 JSON 문자열
   * @throws JsonProcessingException 요청 객체를 JSON으로 직렬화할 수 없는 경우
   */
  private String request(ExplanationJob job) throws JsonProcessingException {
    int remainingMonths =
        Math.max(
            1,
            (int)
                    ChronoUnit.MONTHS.between(
                        YearMonth.from(job.asOfDate()), YearMonth.from(job.targetDate()))
                + 1);
    ObjectNode root = mapper.createObjectNode();
    root.put("planVersionId", job.planVersionId());
    root.put("maxRetry", 2);
    LinkedHashSet<Long> allowedNumbers = new LinkedHashSet<>();
    allowedNumbers.add(job.recommendedMonthlySpending());
    allowedNumbers.add(job.currentAvgVariableSpending());
    allowedNumbers.add((long) remainingMonths);
    allowedNumbers.add(job.targetAmount());
    allowedNumbers.add(job.currentSavedAmount());
    root.set("allowedNumbers", mapper.valueToTree(allowedNumbers));
    ObjectNode plan = root.putObject("plan");
    plan.put("recommendedMonthlySpending", job.recommendedMonthlySpending());
    plan.put("currentAvgVariableSpending", job.currentAvgVariableSpending());
    plan.put("remainingMonths", remainingMonths);
    plan.put("targetAmount", job.targetAmount());
    plan.put("currentSavedAmount", job.currentSavedAmount());
    plan.put("simulationCoverage", job.simulationCoverage());
    plan.put("aggressiveWarning", job.aggressiveWarning());
    return mapper.writeValueAsString(root);
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

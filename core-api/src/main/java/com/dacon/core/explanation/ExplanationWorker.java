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

/** Redis 식별자로 확정 snapshot을 읽어 설명을 생성하고 최종 상태를 저장한다. */
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

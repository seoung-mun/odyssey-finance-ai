package com.dacon.core.policy;

import com.dacon.core.analysis.AnalysisServicePort;
import com.dacon.core.error.ApiException;
import com.dacon.core.policy.PolicyDtos.ConfirmedAward;
import com.dacon.core.policy.PolicyDtos.ConfirmedMonthlyAward;
import com.dacon.core.policy.PolicyDtos.PolicyScenarioRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 저장된 ACTIVE 계획 입력에 확정된 정책 지원만 가정해 내부 계산에 전달한다. */
@Service
public class PolicyScenarioService {
  private static final long MAX_AWARD = 1_000_000_000_000_000L;
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final String NOTICE = "실제 지원 여부와 금액은 신청기관 심사로 확정되며 현재 계획에는 반영되지 않습니다.";
  private static final List<String> PLAN_FIELDS =
      List.of(
          "randomSeed",
          "nPaths",
          "horizonMonths",
          "periodRatios",
          "availableVariableBudget",
          "historicalMonthlyVariableSpending",
          "currentAvgVariableSpending",
          "remainingScheduledExpenses",
          "policySnapshot");

  private final PolicyRepository repository;
  private final AnalysisServicePort analysis;
  private final ObjectMapper mapper;

  public PolicyScenarioService(
      PolicyRepository repository, AnalysisServicePort analysis, ObjectMapper mapper) {
    this.repository = repository;
    this.analysis = analysis;
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public JsonNode compare(
      int userId, long policyVersionId, PolicyScenarioRequest request, String requestId) {
    Award award = award(request.confirmedAward());
    PolicyRepository.ScenarioSnapshot snapshot =
        repository.scenarioSnapshot(userId, request.currentPlanVersionId(), policyVersionId);
    if (snapshot == null) {
      throw error(HttpStatus.NOT_FOUND, "POLICY_SCENARIO_NOT_FOUND", "요청한 계획을 찾을 수 없습니다.");
    }
    validateSnapshot(snapshot, request.supportGoal());
    ObjectNode planInput = planInput(snapshot.inputSnapshot());
    int horizon = planInput.path("horizonMonths").asInt(0);
    if (horizon < 1 || horizon > 120) {
      throw error(HttpStatus.CONFLICT, "PLAN_NOT_CALCULABLE", "계산할 수 없는 계획입니다.");
    }
    Adjustment adjustment = adjustment(award, snapshot, horizon);
    validateArithmetic(planInput, award.amount(), adjustment.months());
    ObjectNode payload = mapper.createObjectNode();
    payload.set("planInput", planInput);
    payload.set("selectedOption", selectedOption(snapshot));
    payload.set("adjustment", adjustment.internal());
    JsonNode result = analysis.policyScenario(write(payload), requestId);
    if (!result.isObject()
        || !result.path("currentPlanSummary").isObject()
        || !result.path("assumedPlanSummary").isObject()) {
      throw error(
          HttpStatus.SERVICE_UNAVAILABLE, "CALCULATION_SERVICE_UNAVAILABLE", "계산 결과를 확인할 수 없습니다.");
    }
    ObjectNode response = mapper.createObjectNode();
    response.set("currentPlanSummary", result.path("currentPlanSummary"));
    response.set("assumedPlanSummary", result.path("assumedPlanSummary"));
    response.set("adjustment", adjustment.publicValue());
    response.put("assumptionNotice", NOTICE);
    ObjectNode source = response.putObject("source");
    source.put("organization", snapshot.organization());
    source.put("officialUrl", snapshot.officialUrl());
    source.put("sourceVersion", snapshot.sourceVersion());
    source.put("lastVerifiedAt", snapshot.lastVerifiedAt().toString());
    source.putArray("locators").add(snapshot.locator());
    return response;
  }

  private Award award(ConfirmedAward value) {
    if (value.amountWon() == null) {
      throw error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "요청 값을 확인해 주세요.");
    }
    long amount = amount(value.amountWon());
    YearMonth end =
        value instanceof ConfirmedMonthlyAward monthly ? yearMonth(monthly.endYearMonth()) : null;
    return new Award(value.type(), amount, yearMonth(value.startYearMonth()), end);
  }

  private void validateSnapshot(PolicyRepository.ScenarioSnapshot snapshot, String supportGoal) {
    if (!"ACTIVE".equals(snapshot.planStatus()) || snapshot.inputSnapshot() == null) {
      throw error(HttpStatus.CONFLICT, "PLAN_NOT_ACTIVE", "현재 활성 계획이 아닙니다.");
    }
    if (snapshot.optionType() == null) {
      throw error(HttpStatus.CONFLICT, "PLAN_OPTION_NOT_SELECTED", "선택한 계획 옵션이 없습니다.");
    }
    if (("PRESET".equals(snapshot.optionType())
            && (snapshot.nominalLevel() == null
                || snapshot.nominalLevel().compareTo(BigDecimal.ZERO) <= 0
                || snapshot.nominalLevel().compareTo(BigDecimal.ONE) >= 0))
        || (!"PRESET".equals(snapshot.optionType()) && !"CUSTOM".equals(snapshot.optionType()))) {
      throw invalidPlan();
    }
    LocalDate today = LocalDate.now(KST);
    if (!"APPROVED".equals(snapshot.reviewStatus())
        || !snapshot.activeSnapshot()
        || !supportGoal.equals(snapshot.supportGoal())
        || !("ONE_TIME_FUNDING".equals(snapshot.calculationMode())
            || "MONTHLY_EXPENSE_REDUCTION".equals(snapshot.calculationMode()))
        || snapshot.adjustmentType() == null
        || snapshot.amountUpperBound() == null
        || snapshot.organization() == null
        || snapshot.officialUrl() == null
        || snapshot.locator() == null
        || snapshot.lastVerifiedAt() == null
        || snapshot.asOfDate() == null
        || snapshot.effectiveFrom() != null && snapshot.effectiveFrom().isAfter(today)
        || snapshot.effectiveTo() != null && snapshot.effectiveTo().isBefore(today)
        || snapshot.sourceVersion() == null
        || !snapshot.sourceVersion().equals(snapshot.ruleSourceVersion())) {
      throw error(HttpStatus.CONFLICT, "POLICY_NOT_CALCULABLE", "계산 가능한 정책이 아닙니다.");
    }
  }

  private Adjustment adjustment(
      Award award, PolicyRepository.ScenarioSnapshot snapshot, int horizon) {
    if (!award.type().equals(snapshot.calculationMode())
        || !award.type().equals(snapshot.adjustmentType())
        || ("ONE_TIME_FUNDING".equals(award.type()) && snapshot.maxMonths() != null)
        || ("MONTHLY_EXPENSE_REDUCTION".equals(award.type()) && snapshot.maxMonths() == null)
        || BigDecimal.valueOf(award.amount()).compareTo(snapshot.amountUpperBound()) > 0) {
      throw invalidAward();
    }
    int start = monthIndex(snapshot.asOfDate(), award.start(), horizon);
    Integer end = null;
    if (award.end() != null) {
      if (snapshot.maxMonths() == null) {
        throw invalidAward();
      }
      end = monthIndex(snapshot.asOfDate(), award.end(), horizon);
      if (end < start || end - start + 1 > snapshot.maxMonths()) {
        throw invalidAward();
      }
    }
    ObjectNode internal = mapper.createObjectNode();
    ObjectNode external = mapper.createObjectNode();
    internal.put("type", award.type());
    external.put("type", award.type());
    internal.put("amountWon", award.amount());
    external.put("amountWon", award.amount());
    internal.put("startMonthIndex", start);
    external.put("startYearMonth", award.start().toString());
    if (end != null) {
      internal.put("endMonthIndex", end);
      external.put("endYearMonth", award.end().toString());
    }
    internal.put("sourceVersion", snapshot.sourceVersion());
    external.put("sourceVersion", snapshot.sourceVersion());
    return new Adjustment(internal, external, end == null ? 1 : end - start + 1);
  }

  private void validateArithmetic(ObjectNode planInput, long amount, int months) {
    JsonNode budget = planInput.path("availableVariableBudget");
    if (!budget.isIntegralNumber() || !budget.canConvertToLong() || budget.asLong() < 0) {
      throw invalidPlan();
    }
    try {
      Math.addExact(budget.asLong(), Math.multiplyExact(amount, months));
    } catch (ArithmeticException exception) {
      throw invalidAward();
    }
  }

  private int monthIndex(LocalDate asOfDate, YearMonth month, int horizon) {
    try {
      int index = Math.toIntExact(ChronoUnit.MONTHS.between(YearMonth.from(asOfDate), month) + 1);
      if (index < 1 || index > horizon) {
        throw invalidAward();
      }
      return index;
    } catch (ArithmeticException exception) {
      throw invalidAward();
    }
  }

  private ObjectNode planInput(String json) {
    try {
      JsonNode source = mapper.readTree(json);
      if (!source.isObject()) {
        throw invalidPlan();
      }
      ObjectNode result = mapper.createObjectNode();
      for (String field : PLAN_FIELDS) {
        if (!source.has(field)) {
          throw invalidPlan();
        }
        result.set(field, source.path(field));
      }
      return result;
    } catch (JsonProcessingException exception) {
      throw invalidPlan();
    }
  }

  private ObjectNode selectedOption(PolicyRepository.ScenarioSnapshot snapshot) {
    ObjectNode result = mapper.createObjectNode();
    result.put("optionType", snapshot.optionType());
    if ("PRESET".equals(snapshot.optionType())) {
      result.put("nominalLevel", snapshot.nominalLevel());
    } else if ("CUSTOM".equals(snapshot.optionType())) {
      result.put("baselineMonthlySpending", snapshot.recommendedMonthlySpending());
    } else {
      throw invalidPlan();
    }
    return result;
  }

  private long amount(JsonNode value) {
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalidAward();
    }
    long amount = value.asLong();
    if (amount < 1 || amount > MAX_AWARD) {
      throw invalidAward();
    }
    return amount;
  }

  private YearMonth yearMonth(String value) {
    return YearMonth.parse(value);
  }

  private String write(JsonNode value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private ApiException invalidAward() {
    return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_POLICY_AWARD", "지원 금액과 기간을 확인해 주세요.");
  }

  private ApiException invalidPlan() {
    return error(HttpStatus.CONFLICT, "PLAN_NOT_CALCULABLE", "계산할 수 없는 계획입니다.");
  }

  private ApiException error(HttpStatus status, String code, String message) {
    return new ApiException(status, code, message);
  }

  private record Award(String type, long amount, YearMonth start, YearMonth end) {}

  private record Adjustment(ObjectNode internal, ObjectNode publicValue, int months) {}
}

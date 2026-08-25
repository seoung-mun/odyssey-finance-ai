package com.dacon.core.plan;

import com.dacon.core.analysis.AnalysisServicePort;
import com.dacon.core.error.ApiException;
import com.dacon.core.explanation.ExplanationQueuePort;
import com.dacon.core.plan.PlanInput.ScheduledInput;
import com.dacon.core.plan.dto.PlanningDtos.DashboardResponse;
import com.dacon.core.plan.dto.PlanningDtos.ExplanationResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanCreation;
import com.dacon.core.plan.dto.PlanningDtos.PlanDetailResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanOptionResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 외부 계산과 짧은 DB command를 분리해 계획 유스케이스를 조율한다. */
@Service
public class PlanningServiceImpl implements PlanningService {
  private static final String PROMPT_VERSION = "v1";

  private final PlanningQueryService queries;
  private final PlanningCommandService commands;
  private final AnalysisServicePort analysis;
  private final ExplanationQueuePort explanations;
  private final ObjectMapper mapper;

  public PlanningServiceImpl(
      PlanningQueryService queries,
      PlanningCommandService commands,
      AnalysisServicePort analysis,
      ExplanationQueuePort explanations,
      ObjectMapper mapper) {
    this.queries = queries;
    this.commands = commands;
    this.analysis = analysis;
    this.explanations = explanations;
    this.mapper = mapper;
  }

  @Override
  public PlanCreation createPlan(int userId, int goalId, String generationType, String requestId) {
    PlanInput input = queries.readPlanInput(userId, goalId);
    if (!input.profileComplete()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "ONBOARDING_INCOMPLETE", "인적 프로필을 먼저 입력해 주세요.");
    }
    if (input.history().size() < 3) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "INSUFFICIENT_HISTORY", "계획 계산에는 최소 3개월의 거래 이력이 필요합니다.");
    }
    if (input.availableVariableBudget() < 0) {
      if (input.availableVariableBudget() == Long.MIN_VALUE) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "금액 범위를 확인해 주세요.");
      }
      SavedPlan saved =
          commands.save(userId, goalId, input, null, generationType, "가용 유동지출 예산이 부족합니다.");
      return new PlanCreation(
          true,
          saved.planVersionId(),
          queries.plan(userId, saved.planVersionId()),
          "가용 유동지출 예산이 부족합니다.",
          Math.negateExact(input.availableVariableBudget()));
    }

    JsonNode calculation = analysis.simulate(requestJson(input), requestId);
    CalculationResponseValidator.validate(calculation, input.horizonMonths());
    SavedPlan saved = commands.save(userId, goalId, input, calculation, generationType, null);
    explanations.publish(saved.planVersionId(), saved.inputHash(), PROMPT_VERSION);
    return new PlanCreation(
        false, saved.planVersionId(), queries.plan(userId, saved.planVersionId()), null, null);
  }

  @Override
  public List<PlanDetailResponse> planVersions(int userId, int goalId, String status) {
    return queries.plans(userId, goalId, status);
  }

  @Override
  public PlanDetailResponse planVersion(int userId, int planVersionId) {
    return queries.plan(userId, planVersionId);
  }

  @Override
  public ExplanationResponse explanation(int userId, int planVersionId) {
    return queries.explanation(userId, planVersionId);
  }

  @Override
  public PlanDetailResponse select(int userId, int planVersionId, int optionId) {
    try {
      return queries.plan(userId, commands.select(userId, planVersionId, optionId));
    } catch (DataIntegrityViolationException exception) {
      throw new ApiException(HttpStatus.CONFLICT, "OPTION_ALREADY_SELECTED", "이미 다른 옵션이 선택되었습니다.");
    }
  }

  @Override
  public PlanOptionResponse customOption(
      int userId, int planVersionId, long monthlySpending, String requestId) {
    CustomOptionSnapshot snapshot = queries.readCustomOptionSnapshot(userId, planVersionId);
    ObjectNode request = snapshot.inputSnapshot().deepCopy();
    request.put("baselineMonthlySpending", monthlySpending);
    JsonNode calculation = analysis.customOption(writeJson(request), requestId);
    CalculationResponseValidator.validateCustom(calculation, snapshot.horizonMonths());
    int optionId = commands.saveCustomOption(userId, snapshot, calculation);
    return queries.option(userId, optionId);
  }

  @Override
  public DashboardResponse dashboard(int userId) {
    return queries.dashboard(userId);
  }

  private String requestJson(PlanInput input) {
    ObjectNode request = mapper.createObjectNode();
    request.put("randomSeed", Integer.toUnsignedLong(input.hashCode()));
    request.put("nPaths", 10_000);
    request.put("horizonMonths", input.horizonMonths());
    request.set("periodRatios", mapper.valueToTree(input.periodRatios()));
    request.put("availableVariableBudget", input.availableVariableBudget());
    request.set("historicalMonthlyVariableSpending", mapper.valueToTree(input.history()));
    request.put("currentAvgVariableSpending", input.currentAverage());
    ObjectNode floor = request.putObject("spendingFloor");
    floor.put("mode", input.floorMode());
    if (input.customFloor() == null) {
      floor.putNull("customMonthlyAmount");
    } else {
      floor.put("customMonthlyAmount", input.customFloor());
    }
    ArrayNode expenses = request.putArray("remainingScheduledExpenses");
    for (ScheduledInput expense : input.scheduledExpenses()) {
      expenses.addObject().put("monthIndex", expense.monthIndex()).put("amount", expense.amount());
    }
    request.set("policySnapshot", input.policySnapshot());
    return writeJson(request);
  }

  private String writeJson(JsonNode request) {
    try {
      return mapper.writeValueAsString(request);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(exception);
    }
  }
}

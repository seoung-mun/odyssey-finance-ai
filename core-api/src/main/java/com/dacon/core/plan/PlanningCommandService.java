package com.dacon.core.plan;

import com.dacon.core.error.ApiException;
import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.goal.FinancialGoalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 목표와 계획 graph의 짧은 DB command transaction을 담당한다. */
@Service
public class PlanningCommandService {
  private final FinancialGoalRepository goals;
  private final PlanVersionRepository plans;
  private final SimulationRunRepository simulations;
  private final PlanOptionRepository options;
  private final PlanBandRepository bands;
  private final PlanningQueryService queries;
  private final ObjectMapper mapper;

  public PlanningCommandService(
      FinancialGoalRepository goals,
      PlanVersionRepository plans,
      SimulationRunRepository simulations,
      PlanOptionRepository options,
      PlanBandRepository bands,
      PlanningQueryService queries,
      ObjectMapper mapper) {
    this.goals = goals;
    this.plans = plans;
    this.simulations = simulations;
    this.options = options;
    this.bands = bands;
    this.queries = queries;
    this.mapper = mapper;
  }

  @Transactional
  public SavedPlan save(
      int userId,
      int goalId,
      PlanInput original,
      JsonNode calculation,
      String generationType,
      String infeasibleReason) {
    PlanInput current = queries.readPlanInputForUpdate(userId, goalId);
    if (!original.equals(current)) {
      throw new ApiException(HttpStatus.CONFLICT, "PLAN_INPUT_CHANGED", "계산 중 입력이 변경되었습니다.");
    }
    plans
        .findByGoalIdAndStatusOrderByVersionNoDesc(goalId, "PROPOSED")
        .forEach(PlanVersion::markStale);
    FinancialGoal goal =
        goals
            .findByIdAndUserId(goalId, userId)
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다."));
    int version = plans.findMaxVersionNo(goalId) + 1;
    PlanVersion plan =
        new PlanVersion(
            goal,
            version,
            generationType,
            calculation == null ? "INFEASIBLE" : "PROPOSED",
            LocalDate.now(PlanningQueryService.KST),
            original.monthlyIncome(),
            original.monthlyFixedCost(),
            original.targetAmount(),
            original.currentSavedAmount(),
            original.targetDate(),
            original.availableVariableBudget(),
            original.currentAverage(),
            original.policySnapshot(),
            calculation == null ? null : "v1",
            infeasibleReason,
            mapper.createArrayNode());
    plans.saveAndFlush(plan);
    if (calculation == null) {
      return new SavedPlan(plan.id(), null);
    }
    JsonNode simulation = calculation.path("simulation");
    ObjectNode resultSummary = simulation.path("resultSummary").deepCopy();
    resultSummary.set("resolvedSpendingFloor", calculation.path("resolvedSpendingFloor"));
    simulations.save(
        new SimulationRun(
            plan,
            simulation.path("method").asText(),
            simulation.path("nPaths").asInt(),
            simulation.path("randomSeed").asLong(),
            simulation.path("inputSnapshot"),
            simulation.path("inputHash").asText(),
            simulation.path("engineVersion").asText(),
            resultSummary));
    List<PlanOption> savedOptions = new ArrayList<>();
    for (JsonNode option : calculation.path("options")) {
      savedOptions.add(options.saveAndFlush(new PlanOption(plan, option)));
    }
    for (JsonNode band : calculation.path("percentileBands")) {
      bands.save(new PlanBand(savedOptions.get(band.path("optionIndex").asInt()), band));
    }
    return new SavedPlan(plan.id(), simulation.path("inputHash").asText());
  }

  @Transactional
  public int select(int userId, int planId, int optionId) {
    PlanVersion plan =
        plans
            .findOwnedForUpdate(userId, planId)
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다."));
    PlanOption option =
        options
            .findForUpdate(planId, optionId)
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다."));
    if (option.selectedAt() != null) {
      throw new ApiException(HttpStatus.CONFLICT, "OPTION_ALREADY_SELECTED", "이미 선택된 옵션입니다.");
    }
    if (!"PROPOSED".equals(plan.status())) {
      throw new ApiException(HttpStatus.CONFLICT, "PLAN_NOT_SELECTABLE", "선택할 수 없는 계획입니다.");
    }
    plans
        .findFirstByGoalIdAndStatusOrderByVersionNoDesc(plan.goal().id(), "ACTIVE")
        .ifPresent(PlanVersion::supersede);
    Instant now = Instant.now();
    option.select(now);
    plan.activate(now);
    return plan.id();
  }

  /** CUSTOM 계산 중 계획 snapshot이 바뀌지 않았는지 잠금 아래 확인하고 옵션만 저장한다. */
  @Transactional
  public int saveCustomOption(int userId, CustomOptionSnapshot original, JsonNode calculation) {
    PlanVersion plan =
        plans
            .findOwnedForUpdate(userId, original.planVersionId())
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다."));
    if (!"PROPOSED".equals(plan.status())) {
      throw new ApiException(HttpStatus.CONFLICT, "PLAN_NOT_SELECTABLE", "선택할 수 없는 계획입니다.");
    }
    SimulationRun current =
        simulations
            .findByPlanVersionId(plan.id())
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "계획 계산 결과가 없습니다."));
    if (!original.inputHash().equals(current.inputHash())
        || !original.inputSnapshot().equals(current.inputSnapshot())) {
      throw new ApiException(HttpStatus.CONFLICT, "PLAN_INPUT_CHANGED", "계산 중 입력이 변경되었습니다.");
    }
    PlanOption option = options.saveAndFlush(new PlanOption(plan, calculation.path("option")));
    for (JsonNode band : calculation.path("percentileBands")) {
      bands.save(new PlanBand(option, band));
    }
    return option.id();
  }
}

package com.dacon.core.plan;

import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.goal.dto.GoalDtos.GoalResponse;
import com.dacon.core.plan.dto.PlanningDtos.ExplanationResponse;
import com.dacon.core.plan.dto.PlanningDtos.PercentileBandResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanDetailResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanOptionResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanSnapshotResponse;
import com.dacon.core.plan.dto.PlanningDtos.SimulationResponse;
import com.dacon.core.plan.dto.PlanningDtos.SpendingFloorResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** JPA entity graph를 공개 계획 DTO로 변환한다. */
@Component
public class PlanningMapper {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private final SimulationRunRepository simulations;
  private final PlanOptionRepository options;
  private final PlanBandRepository bands;

  public PlanningMapper(
      SimulationRunRepository simulations, PlanOptionRepository options, PlanBandRepository bands) {
    this.simulations = simulations;
    this.options = options;
    this.bands = bands;
  }

  public GoalResponse goal(FinancialGoal goal) {
    int remaining =
        Math.max(
            0,
            (int) ChronoUnit.MONTHS.between(YearMonth.now(KST), YearMonth.from(goal.targetDate()))
                + 1);
    return new GoalResponse(
        goal.id(),
        goal.name(),
        goal.targetAmount(),
        goal.currentSavedAmount(),
        goal.targetDate(),
        goal.status(),
        remaining,
        goal.spendingReplanSuppressedUntil(),
        goal.createdAt());
  }

  public PlanDetailResponse plan(PlanVersion plan) {
    SimulationRun simulation = simulations.findByPlanVersionId(plan.id()).orElse(null);
    List<PlanOptionResponse> optionResponses =
        options.findByPlanVersionIdOrderByNominalLevelAscIdAsc(plan.id()).stream()
            .map(this::option)
            .toList();
    return new PlanDetailResponse(
        plan.id(),
        plan.versionNo(),
        plan.generationType(),
        plan.status(),
        plan.asOfDate(),
        plan.infeasibleReason(),
        plan.createdAt(),
        plan.activatedAt(),
        snapshot(plan, simulation),
        optionResponses,
        explanation(plan),
        simulation == null
            ? null
            : new SimulationResponse(
                simulation.method(),
                simulation.nPaths(),
                simulation.engineVersion(),
                simulation.createdAt()));
  }

  public PlanOptionResponse option(PlanOption option) {
    List<PercentileBandResponse> bandResponses =
        bands.findByOptionIdOrderByIdMonthIndex(option.id()).stream()
            .map(
                band ->
                    new PercentileBandResponse(
                        band.monthIndex(),
                        band.metricType(),
                        band.p10Value(),
                        band.p25Value(),
                        band.p50Value(),
                        band.p75Value(),
                        band.p90Value()))
            .toList();
    return new PlanOptionResponse(
        option.id(),
        option.optionType(),
        option.nominalLevel(),
        option.recommendedMonthlySpending(),
        option.requiredReductionRate(),
        option.simulationCoverage(),
        option.historicalFeasibilityRatio(),
        option.aggressiveWarning(),
        option.effectiveMaxReductionRate(),
        option.floorApplied(),
        option.targetCoverageMet(),
        option.selectedAt(),
        bandResponses);
  }

  public ExplanationResponse explanation(PlanVersion plan) {
    List<String> failedNumbers = new ArrayList<>();
    JsonNode failed = plan.explanationFailedNumbers();
    if (failed != null && failed.isArray()) {
      failed.forEach(value -> failedNumbers.add(value.asText()));
    }
    return new ExplanationResponse(
        plan.explanationStatus(),
        plan.explanationText(),
        plan.explanationModel(),
        plan.explanationGeneratedAt(),
        plan.explanationRetryCount(),
        plan.explanationPromptVersion(),
        List.copyOf(failedNumbers));
  }

  private PlanSnapshotResponse snapshot(PlanVersion plan, SimulationRun simulation) {
    JsonNode floor =
        simulation == null ? null : simulation.resultSummary().path("resolvedSpendingFloor");
    SpendingFloorResponse resolved =
        floor != null && floor.isObject()
            ? new SpendingFloorResponse(
                floor.path("mode").asText("OFF"),
                floor.path("requestedMonthlyAmount").asLong(),
                floor.path("effectiveMonthlyAmount").asLong(),
                floor.path("autoHistoryMonths").isInt()
                    ? floor.path("autoHistoryMonths").asInt()
                    : null)
            : new SpendingFloorResponse("OFF", 0, 0, null);
    int remaining =
        (int)
                ChronoUnit.MONTHS.between(
                    YearMonth.from(plan.asOfDate()), YearMonth.from(plan.targetDateSnapshot()))
            + 1;
    return new PlanSnapshotResponse(
        plan.monthlyIncomeSnapshot(),
        plan.monthlyFixedCostSnapshot(),
        plan.targetAmountSnapshot(),
        plan.currentSavedSnapshot(),
        plan.targetDateSnapshot(),
        plan.availableVariableBudget(),
        plan.currentAvgVariableSpending(),
        remaining,
        resolved);
  }
}

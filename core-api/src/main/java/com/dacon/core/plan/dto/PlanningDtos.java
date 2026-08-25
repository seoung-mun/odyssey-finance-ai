package com.dacon.core.plan.dto;

import com.dacon.core.goal.dto.GoalDtos.GoalResponse;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 목표·계획 API의 요청과 응답 DTO를 모은다. */
public final class PlanningDtos {
  private PlanningDtos() {}

  public record PlanRequest(
      @Pattern(regexp = "INITIAL|USER_REQUESTED", message = "계획 생성 유형을 확인해 주세요")
          String generationType) {
    public PlanRequest {
      if (generationType == null) {
        generationType = "INITIAL";
      }
    }
  }

  public record OptionSelection(@Positive int planOptionId) {}

  public record CustomOptionRequest(@PositiveOrZero long monthlySpending) {}

  public record SpendingFloorResponse(
      String mode,
      long requestedMonthlyAmount,
      long effectiveMonthlyAmount,
      Integer autoHistoryMonths) {}

  public record PlanSnapshotResponse(
      long monthlyIncome,
      long monthlyFixedCost,
      long targetAmount,
      long currentSaved,
      LocalDate targetDate,
      long availableVariableBudget,
      long currentAvgVariableSpending,
      int remainingMonths,
      SpendingFloorResponse resolvedSpendingFloor) {}

  public record PercentileBandResponse(
      int monthIndex, String metricType, long p10, long p25, long p50, long p75, long p90) {}

  public record PlanOptionResponse(
      int id,
      String optionType,
      BigDecimal nominalLevel,
      long recommendedMonthlySpending,
      BigDecimal requiredReductionRate,
      BigDecimal simulationCoverage,
      BigDecimal historicalFeasibilityRatio,
      boolean aggressiveWarning,
      BigDecimal effectiveMaxReductionRate,
      boolean floorApplied,
      boolean targetCoverageMet,
      Instant selectedAt,
      List<PercentileBandResponse> percentileBands) {}

  public record ExplanationResponse(
      String status,
      String text,
      String model,
      Instant generatedAt,
      int retryCount,
      String promptVersion,
      List<String> failedNumbers) {}

  public record SimulationResponse(
      String method, int nPaths, String engineVersion, Instant createdAt) {}

  public record PlanDetailResponse(
      int id,
      int versionNo,
      String generationType,
      String status,
      LocalDate asOfDate,
      String infeasibleReason,
      Instant createdAt,
      Instant activatedAt,
      PlanSnapshotResponse snapshot,
      List<PlanOptionResponse> options,
      ExplanationResponse explanation,
      SimulationResponse simulation) {}

  public record PendingProposalResponse(
      int planVersionId, Integer replanEventId, String triggerType, Instant createdAt) {}

  public record MonthProgressResponse(
      LocalDate yearMonth,
      long plannedMonthlySpending,
      long actualToDate,
      double paceRatio,
      int daysElapsed) {}

  public record DashboardResponse(
      GoalResponse goal,
      PlanDetailResponse activePlan,
      PlanOptionResponse selectedOption,
      PendingProposalResponse pendingProposal,
      MonthProgressResponse monthProgress) {}

  public record PlanCreation(
      boolean infeasible,
      int planVersionId,
      PlanDetailResponse detail,
      String infeasibleReason,
      Long shortfallAmount) {}
}

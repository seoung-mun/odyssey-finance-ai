package com.dacon.core.plan;

import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.goal.dto.GoalDtos.GoalResponse;
import com.dacon.core.plan.dto.PlanningDtos.ExplanationResponse;
import com.dacon.core.plan.dto.PlanningDtos.PercentileBandResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanDetailResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanOptionResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanSnapshotResponse;
import com.dacon.core.plan.dto.PlanningDtos.SimulationResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * JPA 엔티티와 연관 저장소 조회 결과를 공개 계획 DTO graph로 변환한다.
 *
 * <p>엔티티 상태는 변경하지 않으며 계산 불가능 계획에는 {@code null} 시뮬레이션 메타데이터를 구성한다.
 */
@Component
public class PlanningMapper {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private final SimulationRunRepository simulations;
  private final PlanOptionRepository options;
  private final PlanBandRepository bands;

  /**
   * 계획 하위 graph 조회에 필요한 저장소를 연결한다.
   *
   * @param simulations 계획별 시뮬레이션 조회 저장소
   * @param options 계획별 선택지 조회 저장소
   * @param bands 선택지별 분위수 밴드 조회 저장소
   */
  public PlanningMapper(
      SimulationRunRepository simulations, PlanOptionRepository options, PlanBandRepository bands) {
    this.simulations = simulations;
    this.options = options;
    this.bands = bands;
  }

  /**
   * 금융 목표와 현재 날짜를 대시보드용 응답으로 변환한다.
   *
   * @param goal 사용자 소유가 확인된 금융 목표
   * @return 남은 개월 수를 0 아래로 내리지 않은 목표 응답
   */
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
        goal.createdAt(),
        null);
  }

  /**
   * 계획 엔티티와 저장된 시뮬레이션·옵션·밴드를 상세 응답으로 조립한다.
   *
   * @param plan 사용자 소유가 확인된 계획 버전
   * @return 계획 스냅샷과 현재 설명 상태를 포함한 상세 응답
   */
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
        snapshot(plan),
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

  /**
   * 계획 옵션과 월 순서의 분위수 밴드를 응답으로 조립한다.
   *
   * @param option 사용자 소유 계획에 속한 옵션
   * @return 계산 지표와 밴드를 포함한 옵션 응답
   */
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
        option.targetCoverageMet(),
        option.selectedAt(),
        bandResponses);
  }

  /**
   * 계획 엔티티의 설명 상태와 실패 숫자 JSON 배열을 공개 응답으로 변환한다.
   *
   * @param plan 사용자 소유가 확인된 계획 버전
   * @return 설명문이 아직 없을 수 있는 현재 설명 응답
   */
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

  /**
   * 계획 생성 당시 금액을 불변 스냅샷 응답으로 조립한다.
   *
   * @param plan 스냅샷 금액을 보유한 계획 버전
   * @return 계획 당시 값만 포함하는 스냅샷 응답
   */
  private PlanSnapshotResponse snapshot(PlanVersion plan) {
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
        remaining);
  }
}

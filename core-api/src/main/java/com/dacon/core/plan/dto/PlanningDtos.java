package com.dacon.core.plan.dto;

import com.dacon.core.goal.dto.GoalDtos.GoalResponse;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 목표·계획 HTTP API가 직렬화하는 요청과 불변 응답 타입의 이름 공간이다. */
public final class PlanningDtos {
  /** 인스턴스 생성을 막는다. */
  private PlanningDtos() {}

  /**
   * 계획 생성 종류를 받으며 값이 생략되면 {@code INITIAL}로 정규화한다.
   *
   * @param generationType {@code INITIAL} 또는 {@code USER_REQUESTED}; 생략 시 {@code INITIAL}
   */
  public record PlanRequest(
      @Pattern(regexp = "INITIAL|USER_REQUESTED", message = "계획 생성 유형을 확인해 주세요")
          String generationType) {
    /** 누락된 생성 종류를 기본 최초 생성 값으로 바꾼다. */
    public PlanRequest {
      if (generationType == null) {
        generationType = "INITIAL";
      }
    }
  }

  /**
   * 계획 옵션 선택 요청이다.
   *
   * @param planOptionId 선택할 양수 계획 옵션 식별자
   */
  public record OptionSelection(@Positive int planOptionId) {}

  /**
   * 사용자 지정 지출액으로 옵션을 계산하는 요청이다.
   *
   * @param monthlySpending CUSTOM 계산에 사용할 0 이상 원 단위 월 유동지출
   */
  public record CustomOptionRequest(@PositiveOrZero long monthlySpending) {}

  /**
   * 계산 엔진이 입력 정책과 이력에서 확정한 유동지출 하한이다.
   *
   * @param mode {@code OFF}, {@code AUTO} 또는 {@code CUSTOM} 정책 모드
   * @param requestedMonthlyAmount 정책이 요청한 원 단위 월 하한
   * @param effectiveMonthlyAmount 계산에 실제 적용된 원 단위 월 하한
   * @param autoHistoryMonths AUTO 산정에 사용한 이력 개월 수; 다른 모드이면 {@code null}
   */
  public record SpendingFloorResponse(
      String mode,
      long requestedMonthlyAmount,
      long effectiveMonthlyAmount,
      Integer autoHistoryMonths) {}

  /**
   * 계획 생성 시점의 목표·프로필·계산 입력과 해석된 지출 하한이다.
   *
   * @param monthlyIncome 원 단위 월 소득
   * @param monthlyFixedCost 원 단위 월 고정비
   * @param targetAmount 원 단위 목표 금액
   * @param currentSaved 원 단위 현재 저축 금액
   * @param targetDate 목표 달성 예정일
   * @param availableVariableBudget 계산 기간 전체의 원 단위 가용 유동지출
   * @param currentAvgVariableSpending 과거 원 단위 월평균 유동지출
   * @param remainingMonths 기준 달과 목표 달을 포함한 계산 개월 수
   * @param resolvedSpendingFloor 계산에 실제 적용된 유동지출 하한
   */
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

  /**
   * 특정 옵션과 월의 누적 저축 분포 분위수다.
   *
   * @param monthIndex 계획 시작을 1로 세는 월 인덱스
   * @param metricType 현재는 {@code CUMULATIVE_SAVINGS}인 지표 종류
   * @param p10 10번째 백분위 원 단위 누적 저축액
   * @param p25 25번째 백분위 원 단위 누적 저축액
   * @param p50 중앙 원 단위 누적 저축액
   * @param p75 75번째 백분위 원 단위 누적 저축액
   * @param p90 90번째 백분위 원 단위 누적 저축액
   */
  public record PercentileBandResponse(
      int monthIndex, String metricType, long p10, long p25, long p50, long p75, long p90) {}

  /**
   * 결정론적 엔진과 몬테카를로가 확정한 하나의 계획 선택지다.
   *
   * @param id 계획 옵션 식별자
   * @param optionType {@code PRESET} 또는 {@code CUSTOM}
   * @param nominalLevel PRESET 명목 수준; CUSTOM이면 {@code null}
   * @param recommendedMonthlySpending 원 단위 권장 월 유동지출
   * @param requiredReductionRate 현재 평균 대비 필요한 절감률
   * @param simulationCoverage 목표에 도달한 시뮬레이션 경로 비율
   * @param historicalFeasibilityRatio 과거 지출이 권장액 이내였던 월 비율
   * @param aggressiveWarning 과도한 절감 경고 여부
   * @param effectiveMaxReductionRate 지출 하한을 반영한 최대 허용 절감률
   * @param floorApplied 지출 하한으로 권장액이 조정됐는지 여부
   * @param targetCoverageMet 하한 적용 뒤 목표 커버리지 충족 여부
   * @param selectedAt 사용자 선택 시각; 미선택이면 {@code null}
   * @param percentileBands 월 순서의 누적 저축 분위수 밴드
   */
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

  /**
   * 계획과 독립적으로 진행되는 설명 생성 상태와 최종 결과다.
   *
   * @param status {@code PENDING}, {@code PROCESSING}, {@code READY}, {@code FALLBACK} 또는 {@code
   *     FAILED}
   * @param text 최종 설명문; 미완료이면 {@code null}
   * @param model 설명 생성 모델; 미생성 또는 fallback이면 {@code null}
   * @param generatedAt 최종 결과 생성 시각; 미완료이면 {@code null}
   * @param retryCount 숫자 검증 재시도 횟수
   * @param promptVersion 계획에 고정된 프롬프트 버전; 계산 불가능 계획이면 {@code null}
   * @param failedNumbers 검증에 실패한 숫자 문자열 목록
   */
  public record ExplanationResponse(
      String status,
      String text,
      String model,
      Instant generatedAt,
      int retryCount,
      String promptVersion,
      List<String> failedNumbers) {}

  /**
   * 저장된 몬테카를로 실행의 재현 메타데이터다.
   *
   * @param method 시뮬레이션 방법
   * @param nPaths 경로 수
   * @param engineVersion 계산 엔진 버전
   * @param createdAt 실행 결과 저장 시각
   */
  public record SimulationResponse(
      String method, int nPaths, String engineVersion, Instant createdAt) {}

  /**
   * 하나의 append-only 계획 버전과 그 하위 계산 결과를 나타낸다.
   *
   * @param id 계획 버전 식별자
   * @param versionNo 목표 안에서의 버전 번호
   * @param generationType 최초 생성 또는 재계획 종류
   * @param status 현재 계획 생명주기 상태
   * @param asOfDate 계산 기준일
   * @param infeasibleReason 계산 불가능 사유; 계산 가능하면 {@code null}
   * @param createdAt 계획 생성 시각
   * @param activatedAt 사용자 선택으로 활성화된 시각; 미활성이면 {@code null}
   * @param snapshot 계산 당시 입력 스냅샷
   * @param options 저장된 PRESET과 CUSTOM 선택지
   * @param explanation 현재 설명 상태와 결과
   * @param simulation 실행 메타데이터; 계산 불가능 계획이면 {@code null}
   */
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

  /**
   * 사용자 결정을 기다리는 최신 제안 계획의 출처다.
   *
   * @param planVersionId 제안 계획 버전 식별자
   * @param replanEventId 제안을 만든 재계획 사건 식별자; 직접 생성이면 {@code null}
   * @param triggerType 재계획 촉발 종류; 직접 생성이면 {@code null}
   * @param createdAt 제안 계획 생성 시각
   */
  public record PendingProposalResponse(
      int planVersionId, Integer replanEventId, String triggerType, Instant createdAt) {}

  /**
   * 현재 달 실제 순지출과 선택 계획의 월 권장액을 비교한 진행 상태다.
   *
   * @param yearMonth 현재 달의 첫 날짜
   * @param plannedMonthlySpending 선택 옵션의 원 단위 월 권장 지출
   * @param actualToDate 현재 시각까지의 원 단위 순지출
   * @param paceRatio 경과 일수로 보정한 계획 대비 지출 속도; 계획액이 0이면 0
   * @param daysElapsed 현재 달에서 경과한 날짜 수
   */
  public record MonthProgressResponse(
      LocalDate yearMonth,
      long plannedMonthlySpending,
      long actualToDate,
      double paceRatio,
      int daysElapsed) {}

  /**
   * 사용자의 현재 활성 목표와 계획 상태를 대시보드 한 화면에 제공한다.
   *
   * @param goal 활성 금융 목표; 없으면 {@code null}
   * @param activePlan 활성 계획; 없으면 {@code null}
   * @param selectedOption 활성 계획에서 선택한 옵션; 없으면 {@code null}
   * @param pendingProposal 결정을 기다리는 제안 계획; 없으면 {@code null}
   * @param monthProgress 선택 옵션의 이번 달 진행 상태; 없으면 {@code null}
   */
  public record DashboardResponse(
      GoalResponse goal,
      PlanDetailResponse activePlan,
      PlanOptionResponse selectedOption,
      PendingProposalResponse pendingProposal,
      MonthProgressResponse monthProgress) {}

  /**
   * 계획 생성 orchestration의 내부 결과다.
   *
   * @param infeasible 현재 입력으로 계산 가능한 계획이 없는지 여부
   * @param planVersionId 항상 저장되는 새 계획 버전 식별자
   * @param detail 저장 직후 조회한 계획 상세
   * @param infeasibleReason 계산 불가능 사유; 정상 계산이면 {@code null}
   * @param shortfallAmount 목표 달성에 부족한 원 단위 금액; 정상 계산이면 {@code null}
   */
  public record PlanCreation(
      boolean infeasible,
      int planVersionId,
      PlanDetailResponse detail,
      String infeasibleReason,
      Long shortfallAmount) {}

  public record ReplanDecision(
      @NotNull @Pattern(regexp = "ACCEPT_NEW_PLAN|KEEP_CURRENT_PLAN", message = "결정을 확인해 주세요")
          String decision) {}

  public record PlanVersionSummary(
      int id,
      int versionNo,
      String generationType,
      String status,
      LocalDate asOfDate,
      String infeasibleReason,
      Instant createdAt,
      Instant activatedAt) {}

  public record ReplanEventResponse(
      int id,
      String triggerType,
      JsonNode triggerDetails,
      PlanVersionSummary sourcePlanVersion,
      PlanVersionSummary proposedPlanVersion,
      String userDecision,
      Instant createdAt,
      Instant decidedAt) {}
}

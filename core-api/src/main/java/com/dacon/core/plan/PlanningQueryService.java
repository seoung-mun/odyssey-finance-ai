package com.dacon.core.plan;

import com.dacon.core.error.ApiException;
import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.goal.FinancialGoalRepository;
import com.dacon.core.goal.ScheduledExpenseRepository;
import com.dacon.core.goal.dto.GoalDtos.GoalPatch;
import com.dacon.core.plan.PlanInput.ScheduledInput;
import com.dacon.core.plan.dto.PlanningDtos.DashboardResponse;
import com.dacon.core.plan.dto.PlanningDtos.ExplanationResponse;
import com.dacon.core.plan.dto.PlanningDtos.MonthProgressResponse;
import com.dacon.core.plan.dto.PlanningDtos.PendingProposalResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanDetailResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanOptionResponse;
import com.dacon.core.transaction.TransactionRepository;
import com.dacon.core.user.dto.FinancialProfileInput;
import com.dacon.core.user.entity.FinancialProfile;
import com.dacon.core.user.repository.FinancialProfileRepository;
import com.dacon.core.user.repository.UserProfileRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 소유권을 확인한 계획 조회와 FastAPI에 전달할 계산 입력 스냅샷 조립을 담당한다.
 *
 * <p>일반 조회는 읽기 전용 트랜잭션이며, 저장 전 재검증용 조회는 호출한 command 트랜잭션 안에서 활성 목표 행을 잠근다.
 */
@Service
public class PlanningQueryService {
  static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final FinancialGoalRepository goals;
  private final FinancialProfileRepository financialProfiles;
  private final UserProfileRepository userProfiles;
  private final ScheduledExpenseRepository scheduledExpenses;
  private final TransactionRepository transactions;
  private final PlanVersionRepository plans;
  private final SimulationRunRepository simulations;
  private final PlanOptionRepository options;
  private final ReplanEventRepository replanEvents;
  private final PlanningMapper mapper;
  private final ObjectMapper objectMapper;

  /**
   * 계획 입력과 응답 graph 조립에 필요한 저장소와 매퍼를 구성한다.
   *
   * @param goals 금융 목표 조회 및 잠금 저장소
   * @param financialProfiles 소득·고정비·지출 하한 조회 저장소
   * @param userProfiles 인적 프로필 완료 여부 조회 저장소
   * @param scheduledExpenses 남은 예정지출 조회 저장소
   * @param transactions 과거 월 지출과 현재 순지출 집계 저장소
   * @param plans 계획 이력과 상태 조회 저장소
   * @param simulations 확정 계산 입력 조회 저장소
   * @param options 계획 옵션 조회 저장소
   * @param replanEvents 제안 계획의 재계획 사건 조회 저장소
   * @param mapper 엔티티 graph를 공개 DTO로 변환할 매퍼
   * @param objectMapper 계산 정책 JSON을 만들 매퍼
   */
  public PlanningQueryService(
      FinancialGoalRepository goals,
      FinancialProfileRepository financialProfiles,
      UserProfileRepository userProfiles,
      ScheduledExpenseRepository scheduledExpenses,
      TransactionRepository transactions,
      PlanVersionRepository plans,
      SimulationRunRepository simulations,
      PlanOptionRepository options,
      ReplanEventRepository replanEvents,
      PlanningMapper mapper,
      ObjectMapper objectMapper) {
    this.goals = goals;
    this.financialProfiles = financialProfiles;
    this.userProfiles = userProfiles;
    this.scheduledExpenses = scheduledExpenses;
    this.transactions = transactions;
    this.plans = plans;
    this.simulations = simulations;
    this.options = options;
    this.replanEvents = replanEvents;
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  /**
   * 사용자 소유 계획 한 건을 상세 응답으로 조회한다.
   *
   * @param userId 계획 소유 사용자 식별자
   * @param planId 조회할 계획 버전 식별자
   * @return 저장된 계획 상세
   * @throws ApiException 계획이 사용자에게 속하지 않거나 존재하지 않는 경우
   */
  @Transactional(readOnly = true)
  public PlanDetailResponse plan(int userId, int planId) {
    return mapper.plan(requirePlan(userId, planId));
  }

  /**
   * 사용자 소유 목표의 계획 이력을 선택적 상태 조건으로 조회한다.
   *
   * @param userId 목표 소유 사용자 식별자
   * @param goalId 조회할 목표 식별자
   * @param status 제한할 계획 상태; 전체를 조회하면 {@code null}
   * @return 최신 버전부터 정렬된 계획 상세 목록
   * @throws ApiException 목표가 사용자에게 속하지 않거나 존재하지 않는 경우
   */
  @Transactional(readOnly = true)
  public List<PlanDetailResponse> plans(int userId, int goalId, String status) {
    requireGoal(userId, goalId);
    List<PlanVersion> values =
        status == null
            ? plans.findByGoalIdOrderByVersionNoDesc(goalId)
            : plans.findByGoalIdAndStatusOrderByVersionNoDesc(goalId, status);
    return values.stream().map(mapper::plan).toList();
  }

  /**
   * 사용자 소유 계획의 현재 설명 결과를 조회한다.
   *
   * @param userId 계획 소유 사용자 식별자
   * @param planId 설명을 조회할 계획 버전 식별자
   * @return 현재 설명 상태와 결과
   * @throws ApiException 계획이 사용자에게 속하지 않거나 존재하지 않는 경우
   */
  @Transactional(readOnly = true)
  public ExplanationResponse explanation(int userId, int planId) {
    return mapper.explanation(requirePlan(userId, planId));
  }

  /**
   * CUSTOM 원격 계산 전에 제안 계획과 기존 입력 JSON의 방어적 복사본을 읽는다.
   *
   * @param userId 계획 소유 사용자 식별자
   * @param planId CUSTOM 옵션을 추가할 계획 버전 식별자
   * @return 계획 상태와 시뮬레이션 동일성 검사용 스냅샷
   * @throws ApiException 계획이 없거나 제안 상태가 아니거나 저장된 계산 기간이 유효하지 않은 경우
   */
  @Transactional(readOnly = true)
  public CustomOptionSnapshot readCustomOptionSnapshot(int userId, int planId) {
    PlanVersion plan = requirePlan(userId, planId);
    if (!"PROPOSED".equals(plan.status())) {
      throw new ApiException(HttpStatus.CONFLICT, "PLAN_NOT_SELECTABLE", "선택할 수 없는 계획입니다.");
    }
    SimulationRun simulation =
        simulations.findByPlanVersionId(planId).orElseThrow(() -> notFound("계획 계산 결과가 없습니다."));
    JsonNode snapshot = simulation.inputSnapshot().deepCopy();
    int horizon = snapshot.path("horizonMonths").asInt(0);
    if (horizon < 1 || horizon > 120) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE, "CALCULATION_SERVICE_UNAVAILABLE", "계산 입력을 확인할 수 없습니다.");
    }
    return new CustomOptionSnapshot(
        planId, plan.status(), simulation.inputHash(), snapshot, horizon);
  }

  /**
   * 사용자 소유 옵션 한 건과 그 분위수 밴드를 조회한다.
   *
   * @param userId 옵션 소유 사용자 식별자
   * @param optionId 조회할 계획 옵션 식별자
   * @return 옵션과 월별 분위수 밴드 응답
   * @throws ApiException 옵션이 사용자에게 속하지 않거나 존재하지 않는 경우
   */
  @Transactional(readOnly = true)
  public PlanOptionResponse option(int userId, int optionId) {
    PlanOption option =
        options
            .findByIdAndPlanVersionGoalUserId(optionId, userId)
            .orElseThrow(() -> notFound("요청한 자원이 없습니다."));
    return mapper.option(option);
  }

  /**
   * 활성 목표와 현재 프로필·거래·예정지출에서 원격 계산 전 입력 스냅샷을 만든다.
   *
   * @param userId 목표 소유 사용자 식별자
   * @param goalId 계산할 활성 목표 식별자
   * @return 현재 시점에 확정된 계산 입력
   * @throws ApiException 목표·프로필이 없거나 기간·금액 범위가 유효하지 않은 경우
   */
  @Transactional(readOnly = true)
  public PlanInput readPlanInput(int userId, int goalId) {
    FinancialGoal goal = requireActiveGoal(userId, goalId, false);
    return input(goal);
  }

  @Transactional(readOnly = true)
  public PlanInput readPlanInputWithFinancial(
      int userId, int goalId, FinancialProfileInput financial) {
    FinancialGoal goal = requireActiveGoal(userId, goalId, false);
    return input(
        goal,
        goal.name(),
        goal.targetAmount(),
        goal.currentSavedAmount(),
        goal.targetDate(),
        financial.monthlyIncome(),
        financial.monthlyFixedCost(),
        financial.spendingFloorMode().name(),
        financial.customMonthlyVariableFloor(),
        null);
  }

  @Transactional(readOnly = true)
  public PlanInput readPlanInputWithGoal(int userId, int goalId, GoalPatch patch) {
    FinancialGoal goal = requireActiveGoal(userId, goalId, false);
    FinancialProfile profile =
        financialProfiles.findById(userId).orElseThrow(() -> notFound("금융 프로필이 없습니다."));
    return input(
        goal,
        patch.name() == null ? goal.name() : patch.name().trim(),
        patch.targetAmount() == null ? goal.targetAmount() : patch.targetAmount(),
        patch.currentSavedAmount() == null ? goal.currentSavedAmount() : patch.currentSavedAmount(),
        patch.targetDate() == null ? goal.targetDate() : patch.targetDate(),
        profile.monthlyIncome(),
        profile.monthlyFixedCost(),
        profile.spendingFloorMode().name(),
        profile.customMonthlyVariableFloor(),
        null);
  }

  @Transactional(readOnly = true)
  public PlanInput readPlanInputWithScheduled(
      int userId, int goalId, List<ScheduledInput> scheduled) {
    FinancialGoal goal = requireActiveGoal(userId, goalId, false);
    FinancialProfile profile =
        financialProfiles.findById(userId).orElseThrow(() -> notFound("금융 프로필이 없습니다."));
    return input(
        goal,
        goal.name(),
        goal.targetAmount(),
        goal.currentSavedAmount(),
        goal.targetDate(),
        profile.monthlyIncome(),
        profile.monthlyFixedCost(),
        profile.spendingFloorMode().name(),
        profile.customMonthlyVariableFloor(),
        scheduled);
  }

  /**
   * command 트랜잭션 안에서 활성 목표를 잠그고 계산 직전과 같은 방식으로 입력 스냅샷을 다시 만든다.
   *
   * @param userId 목표 소유 사용자 식별자
   * @param goalId 잠글 활성 목표 식별자
   * @return 저장 직전의 계산 입력
   * @throws ApiException 목표·프로필이 없거나 기간·금액 범위가 유효하지 않은 경우
   */
  public PlanInput readPlanInputForUpdate(int userId, int goalId) {
    return input(requireActiveGoal(userId, goalId, true));
  }

  /**
   * 사용자 현재 대시보드를 조회한다.
   *
   * @param userId 대시보드를 조회할 사용자 식별자
   * @return 활성 목표가 없으면 모든 필드가 {@code null}인 응답, 있으면 현재 계획 상태 묶음
   */
  @Transactional(readOnly = true)
  public DashboardResponse dashboard(int userId) {
    FinancialGoal goal = goals.findFirstByUserIdAndStatus(userId, "ACTIVE").orElse(null);
    if (goal == null) {
      return new DashboardResponse(null, null, null, null, null);
    }
    PlanVersion active =
        plans.findFirstByGoalIdAndStatusOrderByVersionNoDesc(goal.id(), "ACTIVE").orElse(null);
    PlanVersion proposal =
        plans.findFirstByGoalIdAndStatusOrderByVersionNoDesc(goal.id(), "PROPOSED").orElse(null);
    PlanOption selected =
        active == null
            ? null
            : options.findByPlanVersionIdAndSelectedAtIsNotNull(active.id()).orElse(null);
    PendingProposalResponse pending =
        proposal == null
            ? null
            : replanEvents
                .findByProposedPlanVersionId(proposal.id())
                .map(
                    event ->
                        new PendingProposalResponse(
                            proposal.id(), event.id(), event.triggerType(), proposal.createdAt()))
                .orElse(
                    new PendingProposalResponse(proposal.id(), null, null, proposal.createdAt()));
    return new DashboardResponse(
        mapper.goal(goal),
        active == null ? null : mapper.plan(active),
        selected == null ? null : mapper.option(selected),
        pending,
        monthProgress(userId, selected));
  }

  /**
   * 목표의 현재 프로필·최근 24개월 거래·예정지출·계획 상태를 결정론적 입력으로 조립한다.
   *
   * @param goal 사용자 소유가 확인된 활성 목표
   * @return FastAPI 호출과 저장 전 동일성 비교에 함께 쓰는 입력 스냅샷
   * @throws ApiException 프로필이 없거나 목표 기간·금액 산술이 유효 범위를 벗어난 경우
   */
  private PlanInput input(FinancialGoal goal) {
    int userId = goal.userId();
    FinancialProfile profile =
        financialProfiles.findById(userId).orElseThrow(() -> notFound("금융 프로필이 없습니다."));
    return input(
        goal,
        goal.name(),
        goal.targetAmount(),
        goal.currentSavedAmount(),
        goal.targetDate(),
        profile.monthlyIncome(),
        profile.monthlyFixedCost(),
        profile.spendingFloorMode().name(),
        profile.customMonthlyVariableFloor(),
        null);
  }

  private PlanInput input(
      FinancialGoal goal,
      String goalName,
      long targetAmount,
      long currentSavedAmount,
      LocalDate targetDate,
      long monthlyIncome,
      long monthlyFixedCost,
      String floorMode,
      Long customFloor,
      List<ScheduledInput> scheduledOverride) {
    int userId = goal.userId();
    LocalDate today = LocalDate.now(KST);
    int horizon =
        (int) ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(targetDate)) + 1;
    if (targetDate.isBefore(today) || horizon < 1 || horizon > 120) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_HORIZON", "목표 기간을 확인해 주세요.");
    }
    OffsetDateTime historyFrom =
        YearMonth.from(today).minusMonths(24).atDay(1).atStartOfDay(KST).toOffsetDateTime();
    OffsetDateTime currentMonth =
        YearMonth.from(today).atDay(1).atStartOfDay(KST).toOffsetDateTime();
    List<Long> history =
        transactions.monthlySpendingWindow(userId, historyFrom, currentMonth).stream()
            .map(TransactionRepository.MonthlySpendingAmount::getAmount)
            .toList();
    List<ScheduledInput> scheduled =
        scheduledOverride == null
            ? scheduledExpenses
                .findByUserIdAndStatusAndScheduledDateBetweenOrderByScheduledDateAscIdAsc(
                    userId, "PLANNED", today, targetDate)
                .stream()
                .map(
                    expense ->
                        new ScheduledInput(
                            (int)
                                    ChronoUnit.MONTHS.between(
                                        YearMonth.from(today),
                                        YearMonth.from(expense.scheduledDate()))
                                + 1,
                            expense.amount()))
                .toList()
            : List.copyOf(scheduledOverride);
    long scheduledTotal = sumScheduled(scheduled);
    long currentSpent =
        transactions.sumNetAmount(userId, currentMonth, OffsetDateTime.now(KST).plusNanos(1));
    long available;
    try {
      available =
          Math.subtractExact(
              Math.subtractExact(
                  Math.subtractExact(
                      Math.multiplyExact(
                          Math.subtractExact(monthlyIncome, monthlyFixedCost), horizon),
                      scheduledTotal),
                  currentSpent),
              Math.max(0, targetAmount - currentSavedAmount));
    } catch (ArithmeticException exception) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "금액 범위를 확인해 주세요.");
    }
    long average =
        history.isEmpty()
            ? 0
            : Math.round(history.stream().mapToLong(Long::longValue).average().orElse(0));
    ObjectNode policy = objectMapper.createObjectNode().put("aggressiveWarningPct", 0.2);
    String planState =
        plans.findByGoalIdOrderByVersionNoDesc(goal.id()).stream()
            .findFirst()
            .map(
                value ->
                    value.versionNo() + ":" + ("PROPOSED".equals(value.status()) ? value.id() : ""))
            .orElse("none");
    return new PlanInput(
        userId,
        goal.id(),
        goalName,
        targetAmount,
        currentSavedAmount,
        targetDate,
        monthlyIncome,
        monthlyFixedCost,
        floorMode,
        customFloor,
        history,
        scheduled,
        horizon,
        periodRatios(today, targetDate, horizon),
        available,
        currentSpent,
        average,
        policy,
        userProfiles.existsById(userId),
        planState);
  }

  /**
   * 예정지출을 overflow 검사와 함께 원 단위로 합산한다.
   *
   * @throws ApiException 합계가 {@code long} 범위를 벗어난 경우
   */
  private long sumScheduled(List<ScheduledInput> scheduled) {
    long total = 0;
    try {
      for (ScheduledInput input : scheduled) {
        total = Math.addExact(total, input.amount());
      }
      return total;
    } catch (ArithmeticException exception) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "금액 범위를 확인해 주세요.");
    }
  }

  /** 첫 달과 마지막 달의 포함 일수를 반영한 불변 월별 기간 비율을 만든다. */
  private List<Double> periodRatios(LocalDate today, LocalDate targetDate, int horizon) {
    List<Double> ratios = new ArrayList<>();
    for (int index = 0; index < horizon; index++) {
      ratios.add(1.0);
    }
    if (horizon == 1) {
      ratios.set(
          0,
          (double) (targetDate.getDayOfMonth() - today.getDayOfMonth() + 1)
              / today.lengthOfMonth());
    } else {
      ratios.set(
          0, (double) (today.lengthOfMonth() - today.getDayOfMonth() + 1) / today.lengthOfMonth());
      ratios.set(horizon - 1, (double) targetDate.getDayOfMonth() / targetDate.lengthOfMonth());
    }
    return List.copyOf(ratios);
  }

  /** 선택 옵션이 있을 때 이번 달 누적 실제 지출의 계획 대비 pace를 계산한다. */
  private MonthProgressResponse monthProgress(int userId, PlanOption selected) {
    if (selected == null) {
      return null;
    }
    ZonedDateTime now = ZonedDateTime.now(KST);
    OffsetDateTime start = YearMonth.from(now).atDay(1).atStartOfDay(KST).toOffsetDateTime();
    OffsetDateTime end =
        YearMonth.from(now).plusMonths(1).atDay(1).atStartOfDay(KST).toOffsetDateTime();
    long actual = transactions.sumNetAmount(userId, start, end);
    long planned = selected.recommendedMonthlySpending();
    int days = now.getDayOfMonth();
    double pace =
        planned == 0 ? 0 : (actual * now.toLocalDate().lengthOfMonth()) / (double) (planned * days);
    return new MonthProgressResponse(YearMonth.from(now).atDay(1), planned, actual, pace, days);
  }

  /** 사용자 소유 활성 목표를 조회하며 저장 재검증 시에는 비관적 잠금을 적용한다. */
  private FinancialGoal requireActiveGoal(int userId, int goalId, boolean lock) {
    return (lock
            ? goals.findActiveForUpdate(userId, goalId)
            : goals
                .findByIdAndUserId(goalId, userId)
                .filter(goal -> "ACTIVE".equals(goal.status())))
        .orElseThrow(() -> notFound("요청한 자원이 없습니다."));
  }

  private FinancialGoal requireGoal(int userId, int goalId) {
    return goals.findByIdAndUserId(goalId, userId).orElseThrow(() -> notFound("요청한 자원이 없습니다."));
  }

  private PlanVersion requirePlan(int userId, int planId) {
    return plans.findByIdAndGoalUserId(planId, userId).orElseThrow(() -> notFound("요청한 자원이 없습니다."));
  }

  private ApiException notFound(String message) {
    return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
  }
}

package com.dacon.core.plan;

import com.dacon.core.error.ApiException;
import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.goal.FinancialGoalRepository;
import com.dacon.core.goal.ScheduledExpenseRepository;
import com.dacon.core.plan.PlanInput.ScheduledInput;
import com.dacon.core.plan.dto.PlanningDtos.DashboardResponse;
import com.dacon.core.plan.dto.PlanningDtos.ExplanationResponse;
import com.dacon.core.plan.dto.PlanningDtos.MonthProgressResponse;
import com.dacon.core.plan.dto.PlanningDtos.PendingProposalResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanDetailResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanOptionResponse;
import com.dacon.core.transaction.TransactionRepository;
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

/** 계획용 JPA 조회와 계산 입력 snapshot 조립을 담당한다. */
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

  @Transactional(readOnly = true)
  public PlanDetailResponse plan(int userId, int planId) {
    return mapper.plan(requirePlan(userId, planId));
  }

  @Transactional(readOnly = true)
  public List<PlanDetailResponse> plans(int userId, int goalId, String status) {
    requireGoal(userId, goalId);
    List<PlanVersion> values =
        status == null
            ? plans.findByGoalIdOrderByVersionNoDesc(goalId)
            : plans.findByGoalIdAndStatusOrderByVersionNoDesc(goalId, status);
    return values.stream().map(mapper::plan).toList();
  }

  @Transactional(readOnly = true)
  public ExplanationResponse explanation(int userId, int planId) {
    return mapper.explanation(requirePlan(userId, planId));
  }

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

  @Transactional(readOnly = true)
  public PlanOptionResponse option(int userId, int optionId) {
    PlanOption option =
        options
            .findByIdAndPlanVersionGoalUserId(optionId, userId)
            .orElseThrow(() -> notFound("요청한 자원이 없습니다."));
    return mapper.option(option);
  }

  @Transactional(readOnly = true)
  public PlanInput readPlanInput(int userId, int goalId) {
    FinancialGoal goal = requireActiveGoal(userId, goalId, false);
    return input(goal);
  }

  /** command transaction 안에서 목표를 잠그고 같은 snapshot을 다시 만든다. */
  public PlanInput readPlanInputForUpdate(int userId, int goalId) {
    return input(requireActiveGoal(userId, goalId, true));
  }

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

  private PlanInput input(FinancialGoal goal) {
    int userId = goal.userId();
    FinancialProfile profile =
        financialProfiles.findById(userId).orElseThrow(() -> notFound("금융 프로필이 없습니다."));
    LocalDate today = LocalDate.now(KST);
    int horizon =
        (int) ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(goal.targetDate()))
            + 1;
    if (goal.targetDate().isBefore(today) || horizon < 1 || horizon > 120) {
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
        scheduledExpenses
            .findByUserIdAndStatusAndScheduledDateBetweenOrderByScheduledDateAscIdAsc(
                userId, "PLANNED", today, goal.targetDate())
            .stream()
            .map(
                expense ->
                    new ScheduledInput(
                        (int)
                                ChronoUnit.MONTHS.between(
                                    YearMonth.from(today), YearMonth.from(expense.scheduledDate()))
                            + 1,
                        expense.amount()))
            .toList();
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
                          Math.subtractExact(profile.monthlyIncome(), profile.monthlyFixedCost()),
                          horizon),
                      scheduledTotal),
                  currentSpent),
              Math.max(0, goal.targetAmount() - goal.currentSavedAmount()));
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
        goal.name(),
        goal.targetAmount(),
        goal.currentSavedAmount(),
        goal.targetDate(),
        profile.monthlyIncome(),
        profile.monthlyFixedCost(),
        profile.spendingFloorMode().name(),
        profile.customMonthlyVariableFloor(),
        history,
        scheduled,
        horizon,
        periodRatios(today, goal.targetDate(), horizon),
        available,
        currentSpent,
        average,
        policy,
        userProfiles.existsById(userId),
        planState);
  }

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

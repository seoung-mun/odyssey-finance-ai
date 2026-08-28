package com.dacon.core.goal;

import com.dacon.core.auth.UserAccount;
import com.dacon.core.auth.UserAccountRepository;
import com.dacon.core.error.ApiException;
import com.dacon.core.goal.dto.GoalDtos.GoalPatch;
import com.dacon.core.goal.dto.GoalDtos.GoalRequest;
import com.dacon.core.goal.dto.GoalDtos.GoalResponse;
import com.dacon.core.goal.dto.GoalDtos.ScheduledExpenseInput;
import com.dacon.core.goal.dto.GoalDtos.ScheduledExpensePatch;
import com.dacon.core.goal.dto.GoalDtos.ScheduledExpenseResponse;
import com.dacon.core.plan.PlanVersionRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** repository 질의마다 사용자 ID를 포함해 목표와 예정지출의 소유권을 제한한다. */
@Service
public class GoalServiceImpl implements GoalService {
  static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final UserAccountRepository users;
  private final FinancialGoalRepository goals;
  private final ScheduledExpenseRepository scheduledExpenses;
  private final PlanVersionRepository plans;
  private final GoalReplanService goalReplans;
  private final ScheduledExpenseReplanService expenseReplans;

  /**
   * 사용자·목표·예정지출 저장소로 목표 유스케이스를 구성한다.
   *
   * @param users 목표 소유 사용자 저장소
   * @param goals 금융 목표 저장소
   * @param scheduledExpenses 예정지출 조회 저장소
   */
  @Autowired
  public GoalServiceImpl(
      UserAccountRepository users,
      FinancialGoalRepository goals,
      ScheduledExpenseRepository scheduledExpenses,
      PlanVersionRepository plans,
      GoalReplanService goalReplans,
      ScheduledExpenseReplanService expenseReplans) {
    this.users = users;
    this.goals = goals;
    this.scheduledExpenses = scheduledExpenses;
    this.plans = plans;
    this.goalReplans = goalReplans;
    this.expenseReplans = expenseReplans;
  }

  GoalServiceImpl(
      UserAccountRepository users,
      FinancialGoalRepository goals,
      ScheduledExpenseRepository scheduledExpenses) {
    this(users, goals, scheduledExpenses, null, null, null);
  }

  /** {@inheritDoc} 조회 트랜잭션 안에서 예정지출과 연결 거래를 집계한다. */
  @Override
  @Transactional(readOnly = true)
  public List<ScheduledExpenseResponse> scheduledExpenses(int userId, String status) {
    return scheduledExpenses.findViews(userId, status).stream()
        .map(
            value ->
                new ScheduledExpenseResponse(
                    value.getId(),
                    value.getName(),
                    value.getAmount(),
                    value.getScheduledDate(),
                    value.getStatus(),
                    value.getMatchedTransactionCount(),
                    value.getMatchedAmount(),
                    null))
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(readOnly = true)
  public GoalResponse goal(int userId, int goalId) {
    return response(
        goals
            .findByIdAndUserId(goalId, userId)
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다.")));
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(readOnly = true)
  public List<GoalResponse> goals(int userId, String status) {
    List<FinancialGoal> values =
        status == null
            ? goals.findByUserIdOrderByCreatedAtDesc(userId)
            : goals.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status);
    return values.stream().map(this::response).toList();
  }

  /**
   * {@inheritDoc}
   *
   * <p>ACTIVE 목표 유일성은 DB 제약으로 확정하며 경합 시 409로 변환한다.
   */
  @Override
  @Transactional
  public GoalResponse create(int userId, GoalRequest input) {
    if (!input.targetDate().isAfter(LocalDate.now(KST))) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "목표일은 미래여야 합니다.");
    }
    UserAccount user =
        users
            .findById(userId)
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다."));
    try {
      return response(
          goals.saveAndFlush(
              new FinancialGoal(
                  user,
                  input.name(),
                  input.targetAmount(),
                  input.currentSavedAmount(),
                  input.targetDate())));
    } catch (DataIntegrityViolationException exception) {
      throw new ApiException(HttpStatus.CONFLICT, "ACTIVE_GOAL_EXISTS", "이미 진행 중인 목표가 있습니다.");
    }
  }

  @Override
  public GoalResponse updateGoal(int userId, int goalId, GoalPatch input) {
    if (input.targetDate() != null && !input.targetDate().isAfter(LocalDate.now(KST))) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "목표일은 미래여야 합니다.");
    }
    FinancialGoal goal =
        goals
            .findByIdAndUserId(goalId, userId)
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다."));
    boolean amountChanged =
        input.targetAmount() != null && input.targetAmount() != goal.targetAmount();
    boolean dateChanged =
        input.targetDate() != null && !input.targetDate().equals(goal.targetDate());
    if ((amountChanged || dateChanged)
        && plans != null
        && plans.findFirstByGoalIdAndStatusOrderByVersionNoDesc(goalId, "ACTIVE").isPresent()) {
      GoalReplanService.Result result =
          goalReplans.change(userId, goalId, input, java.util.UUID.randomUUID().toString());
      return response(result.goal(), result.eventId());
    }
    goal.update(
        input.name(),
        input.targetAmount(),
        input.currentSavedAmount(),
        input.targetDate(),
        input.status());
    goals.saveAndFlush(goal);
    return response(goal);
  }

  /**
   * 목표 엔티티를 KST 달력 월 기준 남은 개월 수가 포함된 응답으로 변환한다.
   *
   * @param goal 변환할 사용자 소유 목표
   * @return 현재 월과 목표 월을 모두 포함한 남은 개월 수가 계산된 응답
   */
  private GoalResponse response(FinancialGoal goal) {
    return response(goal, null);
  }

  private GoalResponse response(FinancialGoal goal, Integer eventId) {
    int remainingMonths =
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
        remainingMonths,
        goal.spendingReplanSuppressedUntil(),
        goal.createdAt(),
        eventId);
  }

  @Override
  public ScheduledExpenseResponse createScheduledExpense(int userId, ScheduledExpenseInput input) {
    if (expenseReplans != null) {
      ScheduledExpenseReplanService.Result result =
          expenseReplans.create(userId, input, java.util.UUID.randomUUID().toString());
      if (result != null) return response(result.expense(), result.eventId());
    }
    UserAccount user =
        users
            .findById(userId)
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다."));
    return response(
        scheduledExpenses.saveAndFlush(
            new ScheduledExpense(user, input.name(), input.amount(), input.scheduledDate())));
  }

  @Override
  public ScheduledExpenseResponse updateScheduledExpense(
      int userId, int scheduledExpenseId, ScheduledExpensePatch input) {
    if (expenseReplans != null) {
      ScheduledExpenseReplanService.Result result =
          expenseReplans.update(
              userId, scheduledExpenseId, input, java.util.UUID.randomUUID().toString());
      if (result != null) return response(result.expense(), result.eventId());
    }
    ScheduledExpense expense =
        scheduledExpenses
            .findByIdAndUserId(scheduledExpenseId, userId)
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다."));
    expense.update(input.name(), input.amount(), input.scheduledDate(), input.status());
    scheduledExpenses.saveAndFlush(expense);
    return scheduledExpenses
        .findView(userId, scheduledExpenseId)
        .map(
            value ->
                new ScheduledExpenseResponse(
                    value.getId(),
                    value.getName(),
                    value.getAmount(),
                    value.getScheduledDate(),
                    value.getStatus(),
                    value.getMatchedTransactionCount(),
                    value.getMatchedAmount(),
                    null))
        .orElseGet(() -> response(expense));
  }

  private ScheduledExpenseResponse response(ScheduledExpense expense) {
    return response(expense, null);
  }

  private ScheduledExpenseResponse response(ScheduledExpense expense, Integer eventId) {
    java.util.Optional<ScheduledExpenseRepository.ScheduledExpenseView> view =
        scheduledExpenses.findView(expense.userId(), expense.id());
    if (view.isPresent()) {
      ScheduledExpenseRepository.ScheduledExpenseView value = view.get();
      return new ScheduledExpenseResponse(
          value.getId(),
          value.getName(),
          value.getAmount(),
          value.getScheduledDate(),
          value.getStatus(),
          value.getMatchedTransactionCount(),
          value.getMatchedAmount(),
          eventId);
    }
    return new ScheduledExpenseResponse(
        expense.id(),
        expense.name(),
        expense.amount(),
        expense.scheduledDate(),
        expense.status(),
        0,
        0,
        eventId);
  }
}

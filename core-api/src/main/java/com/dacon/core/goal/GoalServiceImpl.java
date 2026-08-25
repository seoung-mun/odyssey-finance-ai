package com.dacon.core.goal;

import com.dacon.core.auth.UserAccount;
import com.dacon.core.auth.UserAccountRepository;
import com.dacon.core.error.ApiException;
import com.dacon.core.goal.dto.GoalDtos.GoalRequest;
import com.dacon.core.goal.dto.GoalDtos.GoalResponse;
import com.dacon.core.goal.dto.GoalDtos.ScheduledExpenseResponse;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 사용자 소유 목표의 조회·생성을 JPA repository로 수행한다. */
@Service
public class GoalServiceImpl implements GoalService {
  static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final UserAccountRepository users;
  private final FinancialGoalRepository goals;
  private final ScheduledExpenseRepository scheduledExpenses;

  /** 사용자·목표 repository를 주입한다. */
  public GoalServiceImpl(
      UserAccountRepository users,
      FinancialGoalRepository goals,
      ScheduledExpenseRepository scheduledExpenses) {
    this.users = users;
    this.goals = goals;
    this.scheduledExpenses = scheduledExpenses;
  }

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
                    value.getMatchedAmount()))
        .toList();
  }

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

  @Override
  @Transactional(readOnly = true)
  public List<GoalResponse> goals(int userId, String status) {
    List<FinancialGoal> values =
        status == null
            ? goals.findByUserIdOrderByCreatedAtDesc(userId)
            : goals.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status);
    return values.stream().map(this::response).toList();
  }

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

  private GoalResponse response(FinancialGoal goal) {
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
        goal.createdAt());
  }
}

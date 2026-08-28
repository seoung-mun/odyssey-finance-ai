package com.dacon.core.goal;

import com.dacon.core.auth.UserAccountRepository;
import com.dacon.core.error.ApiException;
import com.dacon.core.goal.dto.GoalDtos.ScheduledExpenseInput;
import com.dacon.core.goal.dto.GoalDtos.ScheduledExpensePatch;
import com.dacon.core.plan.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 예정지출 생성·변경을 예정 snapshot으로 선계산하고 원자 저장한다. */
@Service
public class ScheduledExpenseReplanService {
  private final FinancialGoalRepository goals;
  private final ScheduledExpenseRepository expenses;
  private final PlanVersionRepository plans;
  private final PlanningQueryService queries;
  private final PlanningServiceImpl planning;
  private final Command command;

  public ScheduledExpenseReplanService(
      FinancialGoalRepository goals,
      ScheduledExpenseRepository expenses,
      PlanVersionRepository plans,
      PlanningQueryService queries,
      PlanningServiceImpl planning,
      Command command) {
    this.goals = goals;
    this.expenses = expenses;
    this.plans = plans;
    this.queries = queries;
    this.planning = planning;
    this.command = command;
  }

  public Result create(int userId, ScheduledExpenseInput input, String requestId) {
    FinancialGoal goal = active(userId);
    if (goal == null
        || plans.findFirstByGoalIdAndStatusOrderByVersionNoDesc(goal.id(), "ACTIVE").isEmpty())
      return null;
    PlanInput original = queries.readPlanInput(userId, goal.id());
    List<PlanInput.ScheduledInput> proposedExpenses =
        scheduled(userId, goal, null, input.amount(), input.scheduledDate(), "PLANNED");
    PlanInput proposed = queries.readPlanInputWithScheduled(userId, goal.id(), proposedExpenses);
    PlanPreview preview = planning.preview(proposed, requestId);
    Result result = command.create(userId, goal.id(), input, original, proposed, preview);
    if (!preview.infeasible()) planning.publish(result.saved());
    return result;
  }

  public Result update(int userId, int id, ScheduledExpensePatch patch, String requestId) {
    FinancialGoal goal = active(userId);
    if (goal == null
        || plans.findFirstByGoalIdAndStatusOrderByVersionNoDesc(goal.id(), "ACTIVE").isEmpty())
      return null;
    ScheduledExpense current =
        expenses
            .findByIdAndUserId(id, userId)
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다."));
    long amount = patch.amount() == null ? current.amount() : patch.amount();
    LocalDate date =
        patch.scheduledDate() == null ? current.scheduledDate() : patch.scheduledDate();
    String status = patch.status() == null ? current.status() : patch.status();
    PlanInput original = queries.readPlanInput(userId, goal.id());
    PlanInput proposed =
        queries.readPlanInputWithScheduled(
            userId, goal.id(), scheduled(userId, goal, id, amount, date, status));
    PlanPreview preview = planning.preview(proposed, requestId);
    Result result = command.update(userId, goal.id(), id, patch, original, proposed, preview);
    if (!preview.infeasible()) planning.publish(result.saved());
    return result;
  }

  private List<PlanInput.ScheduledInput> scheduled(
      int userId,
      FinancialGoal goal,
      Integer excluded,
      long amount,
      LocalDate date,
      String status) {
    LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    List<PlanInput.ScheduledInput> result = new ArrayList<>();
    List<ScheduledExpense> existing =
        expenses
            .findByUserIdAndStatusAndScheduledDateBetweenOrderByScheduledDateAscIdAsc(
                userId, "PLANNED", today, goal.targetDate())
            .stream()
            .filter(value -> excluded == null || value.id() != excluded)
            .toList();
    existing.forEach(value -> result.add(item(today, value.scheduledDate(), value.amount())));
    if ("PLANNED".equals(status) && !date.isBefore(today) && !date.isAfter(goal.targetDate())) {
      long targetId = excluded == null ? Long.MAX_VALUE : excluded;
      int index = 0;
      while (index < existing.size()
          && (existing.get(index).scheduledDate().isBefore(date)
              || (existing.get(index).scheduledDate().isEqual(date)
                  && existing.get(index).id() < targetId))) index++;
      result.add(index, item(today, date, amount));
    }
    return result;
  }

  private PlanInput.ScheduledInput item(LocalDate today, LocalDate date, long amount) {
    return new PlanInput.ScheduledInput(
        (int) ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(date)) + 1, amount);
  }

  private FinancialGoal active(int userId) {
    return goals.findFirstByUserIdAndStatus(userId, "ACTIVE").orElse(null);
  }

  @Service
  public static class Command {
    private final ScheduledExpenseRepository expenses;
    private final FinancialGoalRepository goals;
    private final PlanVersionRepository plans;
    private final ReplanEventRepository events;
    private final UserAccountRepository users;
    private final PlanningQueryService queries;
    private final PlanningCommandService commands;
    private final ObjectMapper mapper;

    public Command(
        ScheduledExpenseRepository expenses,
        FinancialGoalRepository goals,
        PlanVersionRepository plans,
        ReplanEventRepository events,
        UserAccountRepository users,
        PlanningQueryService queries,
        PlanningCommandService commands,
        ObjectMapper mapper) {
      this.expenses = expenses;
      this.goals = goals;
      this.plans = plans;
      this.events = events;
      this.users = users;
      this.queries = queries;
      this.commands = commands;
      this.mapper = mapper;
    }

    @Transactional
    public Result create(
        int userId,
        int goalId,
        ScheduledExpenseInput input,
        PlanInput original,
        PlanInput proposed,
        PlanPreview preview) {
      verify(userId, goalId, original);
      PlanVersion source = source(goalId);
      ScheduledExpense expense =
          expenses.save(
              new ScheduledExpense(
                  users.getReferenceById(userId),
                  input.name(),
                  input.amount(),
                  input.scheduledDate()));
      return finish(userId, goalId, expense, source, proposed, preview);
    }

    @Transactional
    public Result update(
        int userId,
        int goalId,
        int id,
        ScheduledExpensePatch patch,
        PlanInput original,
        PlanInput proposed,
        PlanPreview preview) {
      verify(userId, goalId, original);
      PlanVersion source = source(goalId);
      ScheduledExpense expense = expenses.findByIdAndUserId(id, userId).orElseThrow();
      expense.update(patch.name(), patch.amount(), patch.scheduledDate(), patch.status());
      return finish(userId, goalId, expense, source, proposed, preview);
    }

    private void verify(int userId, int goalId, PlanInput original) {
      if (!original.equals(queries.readPlanInputForUpdate(userId, goalId)))
        throw new ApiException(HttpStatus.CONFLICT, "PLAN_INPUT_CHANGED", "계산 중 입력이 변경되었습니다.");
    }

    private PlanVersion source(int goalId) {
      return plans.findFirstByGoalIdAndStatusOrderByVersionNoDesc(goalId, "ACTIVE").orElseThrow();
    }

    private Result finish(
        int userId,
        int goalId,
        ScheduledExpense expense,
        PlanVersion source,
        PlanInput proposed,
        PlanPreview preview) {
      SavedPlan saved =
          commands.save(
              userId,
              goalId,
              proposed,
              preview.calculation(),
              "TRIGGERED_REPLAN",
              preview.infeasibleReason());
      ReplanEvent event =
          events.save(
              new ReplanEvent(
                  users.getReferenceById(userId),
                  goals.getReferenceById(goalId),
                  source,
                  "SCHEDULED_EXPENSE_CHANGED",
                  mapper.createObjectNode()));
      event.attach(plans.findById(saved.planVersionId()).orElseThrow());
      return new Result(expense, event.id(), saved);
    }
  }

  public record Result(ScheduledExpense expense, int eventId, SavedPlan saved) {}
}

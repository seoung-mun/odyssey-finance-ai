package com.dacon.core.goal;

import com.dacon.core.auth.UserAccountRepository;
import com.dacon.core.error.ApiException;
import com.dacon.core.goal.dto.GoalDtos.GoalPatch;
import com.dacon.core.plan.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 목표 금액·기한 변경을 선계산 뒤 원자 저장한다. */
@Service
public class GoalReplanService {
  private final PlanningQueryService queries;
  private final PlanningServiceImpl planning;
  private final Command command;

  public GoalReplanService(
      PlanningQueryService queries, PlanningServiceImpl planning, Command command) {
    this.queries = queries;
    this.planning = planning;
    this.command = command;
  }

  public Result change(int userId, int goalId, GoalPatch patch, String requestId) {
    PlanInput original = queries.readPlanInput(userId, goalId);
    PlanInput proposed = queries.readPlanInputWithGoal(userId, goalId, patch);
    PlanPreview preview = planning.preview(proposed, requestId);
    Result result = command.apply(userId, goalId, patch, original, proposed, preview);
    if (!preview.infeasible()) planning.publish(result.saved());
    return result;
  }

  @Service
  public static class Command {
    private final FinancialGoalRepository goals;
    private final PlanVersionRepository plans;
    private final ReplanEventRepository events;
    private final UserAccountRepository users;
    private final PlanningQueryService queries;
    private final PlanningCommandService commands;
    private final ObjectMapper mapper;

    public Command(
        FinancialGoalRepository goals,
        PlanVersionRepository plans,
        ReplanEventRepository events,
        UserAccountRepository users,
        PlanningQueryService queries,
        PlanningCommandService commands,
        ObjectMapper mapper) {
      this.goals = goals;
      this.plans = plans;
      this.events = events;
      this.users = users;
      this.queries = queries;
      this.commands = commands;
      this.mapper = mapper;
    }

    @Transactional
    public Result apply(
        int userId,
        int goalId,
        GoalPatch patch,
        PlanInput original,
        PlanInput proposed,
        PlanPreview preview) {
      if (!original.equals(queries.readPlanInputForUpdate(userId, goalId))) {
        throw new ApiException(HttpStatus.CONFLICT, "PLAN_INPUT_CHANGED", "계산 중 입력이 변경되었습니다.");
      }
      FinancialGoal goal = goals.findByIdAndUserId(goalId, userId).orElseThrow();
      String type =
          patch.targetAmount() != null && patch.targetAmount() != goal.targetAmount()
              ? "GOAL_AMOUNT_CHANGED"
              : "GOAL_DATE_CHANGED";
      long beforeAmount = goal.targetAmount();
      PlanVersion source =
          plans.findFirstByGoalIdAndStatusOrderByVersionNoDesc(goalId, "ACTIVE").orElseThrow();
      goal.update(
          patch.name(),
          patch.targetAmount(),
          patch.currentSavedAmount(),
          patch.targetDate(),
          patch.status());
      SavedPlan saved =
          commands.save(
              userId,
              goalId,
              proposed,
              preview.calculation(),
              "TRIGGERED_REPLAN",
              preview.infeasibleReason());
      ObjectNode details = mapper.createObjectNode();
      if (type.equals("GOAL_AMOUNT_CHANGED")) {
        details.put("before", beforeAmount).put("after", proposed.targetAmount());
      } else {
        details
            .put("before", original.targetDate().toString())
            .put("after", proposed.targetDate().toString());
      }
      ReplanEvent event =
          events.save(new ReplanEvent(users.getReferenceById(userId), goal, source, type, details));
      event.attach(plans.findById(saved.planVersionId()).orElseThrow());
      return new Result(goal, event.id(), saved);
    }
  }

  public record Result(FinancialGoal goal, int eventId, SavedPlan saved) {}
}

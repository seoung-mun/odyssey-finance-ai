package com.dacon.core.user;

import com.dacon.core.auth.UserAccountRepository;
import com.dacon.core.error.ApiException;
import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.goal.FinancialGoalRepository;
import com.dacon.core.plan.PlanInput;
import com.dacon.core.plan.PlanPreview;
import com.dacon.core.plan.PlanVersion;
import com.dacon.core.plan.PlanVersionRepository;
import com.dacon.core.plan.PlanningCommandService;
import com.dacon.core.plan.PlanningQueryService;
import com.dacon.core.plan.PlanningServiceImpl;
import com.dacon.core.plan.ReplanEvent;
import com.dacon.core.plan.ReplanEventRepository;
import com.dacon.core.plan.SavedPlan;
import com.dacon.core.user.dto.FinancialProfileInput;
import com.dacon.core.user.dto.FinancialProfileResponse;
import com.dacon.core.user.entity.FinancialProfile;
import com.dacon.core.user.entity.ReplanOutcome;
import com.dacon.core.user.repository.FinancialProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 금융정보 변경 예정값을 먼저 계산하고 잠금 아래 변경·계획·사건을 함께 저장한다. */
@Service
public class FinancialReplanService {
  private final PlanningQueryService queries;
  private final PlanningServiceImpl planning;
  private final FinancialChangeCommand command;

  public FinancialReplanService(
      PlanningQueryService queries, PlanningServiceImpl planning, FinancialChangeCommand command) {
    this.queries = queries;
    this.planning = planning;
    this.command = command;
  }

  public FinancialProfileResponse change(
      int userId, FinancialGoal goal, FinancialProfileInput input, String requestId) {
    PlanInput original = queries.readPlanInput(userId, goal.id());
    PlanInput proposed = queries.readPlanInputWithFinancial(userId, goal.id(), input);
    PlanPreview preview = planning.preview(proposed, requestId);
    FinancialChangeResult result =
        command.apply(userId, goal.id(), input, original, proposed, preview);
    if (!preview.infeasible()) {
      planning.publish(result.saved());
    }
    return result.response();
  }

  @Service
  public static class FinancialChangeCommand {
    private final FinancialProfileRepository profiles;
    private final FinancialGoalRepository goals;
    private final PlanVersionRepository plans;
    private final ReplanEventRepository events;
    private final UserAccountRepository users;
    private final PlanningQueryService queries;
    private final PlanningCommandService commands;
    private final ObjectMapper mapper;

    public FinancialChangeCommand(
        FinancialProfileRepository profiles,
        FinancialGoalRepository goals,
        PlanVersionRepository plans,
        ReplanEventRepository events,
        UserAccountRepository users,
        PlanningQueryService queries,
        PlanningCommandService commands,
        ObjectMapper mapper) {
      this.profiles = profiles;
      this.goals = goals;
      this.plans = plans;
      this.events = events;
      this.users = users;
      this.queries = queries;
      this.commands = commands;
      this.mapper = mapper;
    }

    @Transactional
    public FinancialChangeResult apply(
        int userId,
        int goalId,
        FinancialProfileInput input,
        PlanInput original,
        PlanInput proposed,
        PlanPreview preview) {
      if (!original.equals(queries.readPlanInputForUpdate(userId, goalId))) {
        throw new ApiException(HttpStatus.CONFLICT, "PLAN_INPUT_CHANGED", "계산 중 입력이 변경되었습니다.");
      }
      FinancialProfile profile = profiles.findById(userId).orElseThrow();
      String trigger =
          profile.monthlyIncome() != input.monthlyIncome()
              ? "INCOME_CHANGED"
              : "FIXED_COST_CHANGED";
      ObjectNode details = mapper.createObjectNode();
      details.put(
          "before",
          trigger.equals("INCOME_CHANGED") ? profile.monthlyIncome() : profile.monthlyFixedCost());
      details.put(
          "after",
          trigger.equals("INCOME_CHANGED") ? input.monthlyIncome() : input.monthlyFixedCost());
      PlanVersion source =
          plans.findFirstByGoalIdAndStatusOrderByVersionNoDesc(goalId, "ACTIVE").orElseThrow();
      profile.update(input);
      profiles.save(profile);
      SavedPlan saved =
          commands.save(
              userId,
              goalId,
              proposed,
              preview.calculation(),
              "TRIGGERED_REPLAN",
              preview.infeasibleReason());
      PlanVersion proposal = plans.findById(saved.planVersionId()).orElseThrow();
      ReplanEvent event =
          events.save(
              new ReplanEvent(
                  users.getReferenceById(userId),
                  goals.getReferenceById(goalId),
                  source,
                  trigger,
                  details));
      event.attach(proposal);
      FinancialProfileResponse response =
          new FinancialProfileResponse(
              profile.monthlyIncome(),
              profile.monthlyFixedCost(),
              profile.updatedAt(),
              event.id(),
              preview.infeasible() ? ReplanOutcome.INFEASIBLE : ReplanOutcome.PROPOSED,
              saved.planVersionId(),
              preview.shortfallAmount());
      return new FinancialChangeResult(response, saved);
    }
  }

  public record FinancialChangeResult(FinancialProfileResponse response, SavedPlan saved) {}
}

package com.dacon.core.plan;

import com.dacon.core.auth.UserAccountRepository;
import com.dacon.core.error.ApiException;
import com.dacon.core.goal.FinancialGoalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 수동 재계획의 계산 전 DB 무변경과 plan/event 원자 저장을 보장한다. */
@Service
public class ManualReplanService {
  private final PlanningQueryService queries;
  private final PlanningServiceImpl planning;
  private final Command command;

  public ManualReplanService(
      PlanningQueryService queries, PlanningServiceImpl planning, Command command) {
    this.queries = queries;
    this.planning = planning;
    this.command = command;
  }

  public ReplanService.ReplanRequestResult request(int userId, int goalId, String requestId) {
    PlanInput input = queries.readPlanInput(userId, goalId);
    PlanPreview preview = planning.preview(input, requestId);
    Result result = command.save(userId, goalId, input, preview);
    if (!preview.infeasible()) planning.publish(result.saved());
    return new ReplanService.ReplanRequestResult(
        result.eventId(),
        new com.dacon.core.plan.dto.PlanningDtos.PlanCreation(
            preview.infeasible(),
            result.saved().planVersionId(),
            queries.plan(userId, result.saved().planVersionId()),
            preview.infeasibleReason(),
            preview.shortfallAmount()));
  }

  @Service
  public static class Command {
    private final PlanningQueryService queries;
    private final PlanningCommandService commands;
    private final PlanVersionRepository plans;
    private final ReplanEventRepository events;
    private final FinancialGoalRepository goals;
    private final UserAccountRepository users;
    private final ObjectMapper mapper;

    public Command(
        PlanningQueryService queries,
        PlanningCommandService commands,
        PlanVersionRepository plans,
        ReplanEventRepository events,
        FinancialGoalRepository goals,
        UserAccountRepository users,
        ObjectMapper mapper) {
      this.queries = queries;
      this.commands = commands;
      this.plans = plans;
      this.events = events;
      this.goals = goals;
      this.users = users;
      this.mapper = mapper;
    }

    @Transactional
    public Result save(int userId, int goalId, PlanInput input, PlanPreview preview) {
      if (!input.equals(queries.readPlanInputForUpdate(userId, goalId))) {
        throw new ApiException(HttpStatus.CONFLICT, "PLAN_INPUT_CHANGED", "계산 중 입력이 변경되었습니다.");
      }
      PlanVersion source =
          plans.findFirstByGoalIdAndStatusOrderByVersionNoDesc(goalId, "ACTIVE").orElseThrow();
      SavedPlan saved =
          commands.save(
              userId,
              goalId,
              input,
              preview.calculation(),
              "USER_REQUESTED",
              preview.infeasibleReason());
      ReplanEvent event =
          events.save(
              new ReplanEvent(
                  users.getReferenceById(userId),
                  goals.getReferenceById(goalId),
                  source,
                  "USER_REQUESTED",
                  mapper.createObjectNode()));
      event.attach(plans.findById(saved.planVersionId()).orElseThrow());
      return new Result(event.id(), saved);
    }
  }

  public record Result(int eventId, SavedPlan saved) {}
}

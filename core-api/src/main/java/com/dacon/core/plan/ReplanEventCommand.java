package com.dacon.core.plan;

import com.dacon.core.auth.UserAccountRepository;
import com.dacon.core.error.ApiException;
import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.goal.FinancialGoalRepository;
import com.dacon.core.transaction.Transaction;
import com.dacon.core.transaction.TransactionRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 자동 event 생성의 목표 잠금과 checkpoint 멱등성을 한 DB transaction에 둔다. */
@Service
public class ReplanEventCommand {
  private final ReplanEventRepository events;
  private final FinancialGoalRepository goals;
  private final PlanVersionRepository plans;
  private final UserAccountRepository users;
  private final TransactionRepository transactions;
  private final PlanningQueryService queries;
  private final PlanningCommandService commands;

  public ReplanEventCommand(
      ReplanEventRepository events,
      FinancialGoalRepository goals,
      PlanVersionRepository plans,
      UserAccountRepository users,
      TransactionRepository transactions,
      PlanningQueryService queries,
      PlanningCommandService commands) {
    this.events = events;
    this.goals = goals;
    this.plans = plans;
    this.users = users;
    this.transactions = transactions;
    this.queries = queries;
    this.commands = commands;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Integer create(
      int userId, int goalId, String type, ObjectNode details, Long transactionId) {
    FinancialGoal goal =
        goals
            .findActiveForUpdate(userId, goalId)
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다."));
    if ("CUMULATIVE_OVERSPENDING".equals(type)) {
      LocalDate today = LocalDate.now(PlanningQueryService.KST);
      Instant from = today.atStartOfDay(PlanningQueryService.KST).toInstant();
      if (events.existsByGoalIdAndTriggerTypeAndCreatedAtBetween(
          goalId, type, from, from.plusSeconds(86_400))) {
        return null;
      }
    }
    PlanVersion source =
        plans
            .findFirstByGoalIdAndStatusOrderByVersionNoDesc(goalId, "ACTIVE")
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.CONFLICT, "ACTIVE_PLAN_REQUIRED", "활성 계획이 필요합니다."));
    Transaction tx =
        transactionId == null
            ? null
            : transactions.findByIdAndUserId(transactionId, userId).orElseThrow();
    ReplanEvent event =
        tx == null
            ? new ReplanEvent(users.getReferenceById(userId), goal, source, type, details)
            : new ReplanEvent(users.getReferenceById(userId), goal, source, tx, type, details);
    return events.save(event).id();
  }

  @Transactional(readOnly = true)
  public int retryGoalId(int userId, int eventId) {
    ReplanEvent event =
        events
            .findByIdAndUserId(eventId, userId)
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다."));
    if (event.userDecision() != null || event.proposedPlanVersion() != null) {
      throw new ApiException(HttpStatus.CONFLICT, "REPLAN_NOT_RETRYABLE", "재시도할 수 없는 이벤트입니다.");
    }
    return event.sourcePlanVersion().goal().id();
  }

  @Transactional
  public SavedPlan saveProposal(
      int userId, int goalId, int eventId, PlanInput input, PlanPreview preview) {
    if (!input.equals(queries.readPlanInputForUpdate(userId, goalId))) {
      throw new ApiException(HttpStatus.CONFLICT, "PLAN_INPUT_CHANGED", "계산 중 입력이 변경되었습니다.");
    }
    ReplanEvent event = events.findOwnedForUpdate(eventId, userId).orElseThrow();
    SavedPlan saved =
        commands.save(
            userId,
            goalId,
            input,
            preview.calculation(),
            "TRIGGERED_REPLAN",
            preview.infeasibleReason());
    event.attach(plans.findById(saved.planVersionId()).orElseThrow());
    return saved;
  }
}

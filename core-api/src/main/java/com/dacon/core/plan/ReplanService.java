package com.dacon.core.plan;

import com.dacon.core.auth.UserAccountRepository;
import com.dacon.core.error.ApiException;
import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.goal.FinancialGoalRepository;
import com.dacon.core.plan.dto.PlanningDtos.PlanCreation;
import com.dacon.core.plan.dto.PlanningDtos.PlanDetailResponse;
import com.dacon.core.plan.dto.PlanningDtos.PlanVersionSummary;
import com.dacon.core.plan.dto.PlanningDtos.ReplanEventResponse;
import com.dacon.core.transaction.Transaction;
import com.dacon.core.transaction.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 재계획 사건의 생성·계산 연결·결정을 조율한다. */
@Service
public class ReplanService {
  private final ReplanEventRepository events;
  private final FinancialGoalRepository goals;
  private final PlanVersionRepository plans;
  private final UserAccountRepository users;
  private final ObjectMapper mapper;
  private final TransactionRepository transactions;
  private final ManualReplanService manual;
  private final ReplanEventCommand eventCommand;
  private final PlanningServiceImpl planningImpl;
  private final PlanningQueryService queries;

  public ReplanService(
      ReplanEventRepository events,
      FinancialGoalRepository goals,
      PlanVersionRepository plans,
      UserAccountRepository users,
      ObjectMapper mapper,
      TransactionRepository transactions,
      ManualReplanService manual,
      ReplanEventCommand eventCommand,
      PlanningServiceImpl planningImpl,
      PlanningQueryService queries) {
    this.events = events;
    this.goals = goals;
    this.plans = plans;
    this.users = users;
    this.mapper = mapper;
    this.transactions = transactions;
    this.manual = manual;
    this.eventCommand = eventCommand;
    this.planningImpl = planningImpl;
    this.queries = queries;
  }

  @Transactional(readOnly = true)
  public List<ReplanEventResponse> events(int userId, int goalId) {
    requireGoal(userId, goalId);
    return events.findByGoalIdAndUserIdOrderByCreatedAtDesc(goalId, userId).stream()
        .map(this::response)
        .toList();
  }

  public ReplanRequestResult request(int userId, int goalId, String requestId) {
    return manual.request(userId, goalId, requestId);
  }

  public void trigger(
      int userId,
      int goalId,
      String triggerType,
      ObjectNode details,
      Long transactionId,
      String requestId) {
    Integer eventId = eventCommand.create(userId, goalId, triggerType, details, transactionId);
    if (eventId == null) return;
    try {
      PlanInput input = queries.readPlanInput(userId, goalId);
      PlanPreview preview = planningImpl.preview(input, requestId);
      SavedPlan saved = eventCommand.saveProposal(userId, goalId, eventId, input, preview);
      if (!preview.infeasible()) planningImpl.publish(saved);
    } catch (RuntimeException ignored) {
      // 거래는 이미 commit됐다. proposal 없는 event가 retry 경로를 보존한다.
    }
  }

  public PlanDetailResponse retry(int userId, int eventId, String requestId) {
    int goalId = eventCommand.retryGoalId(userId, eventId);
    PlanInput input = queries.readPlanInput(userId, goalId);
    PlanPreview preview = planningImpl.preview(input, requestId);
    SavedPlan saved = eventCommand.saveProposal(userId, goalId, eventId, input, preview);
    if (!preview.infeasible()) planningImpl.publish(saved);
    return queries.plan(userId, saved.planVersionId());
  }

  @Transactional
  public ReplanEventResponse decide(int userId, int eventId, String decision) {
    ReplanEvent event = events.findOwnedForUpdate(eventId, userId).orElseThrow(this::notFound);
    if (event.userDecision() != null) {
      throw new ApiException(HttpStatus.CONFLICT, "DECISION_ALREADY_MADE", "이미 결정된 이벤트입니다.");
    }
    if (event.proposedPlanVersion() == null) {
      throw new ApiException(HttpStatus.CONFLICT, "REPLAN_NOT_READY", "결정할 제안 계획이 없습니다.");
    }
    event.decide(decision, Instant.now());
    if ("KEEP_CURRENT_PLAN".equals(decision)) {
      LocalDate today = LocalDate.now(PlanningQueryService.KST);
      event.goal().suppressSpendingReplanUntil(today.withDayOfMonth(today.lengthOfMonth()));
    }
    return response(event);
  }

  @Transactional
  public int createEvent(int userId, int goalId, String triggerType, ObjectNode details) {
    return createEvent(userId, goalId, triggerType, details, null);
  }

  @Transactional
  public int createEvent(
      int userId, int goalId, String triggerType, ObjectNode details, Long transactionId) {
    FinancialGoal goal = requireGoal(userId, goalId);
    PlanVersion source =
        plans
            .findFirstByGoalIdAndStatusOrderByVersionNoDesc(goalId, "ACTIVE")
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.CONFLICT, "ACTIVE_PLAN_REQUIRED", "활성 계획이 필요합니다."));
    Transaction transaction =
        transactionId == null
            ? null
            : transactions.findByIdAndUserId(transactionId, userId).orElseThrow(this::notFound);
    ReplanEvent event =
        transaction == null
            ? new ReplanEvent(users.getReferenceById(userId), goal, source, triggerType, details)
            : new ReplanEvent(
                users.getReferenceById(userId), goal, source, transaction, triggerType, details);
    return events.save(event).id();
  }

  @Transactional
  public void attach(int userId, int eventId, int planId) {
    ReplanEvent event = events.findOwnedForUpdate(eventId, userId).orElseThrow(this::notFound);
    PlanVersion plan = plans.findByIdAndGoalUserId(planId, userId).orElseThrow(this::notFound);
    event.attach(plan);
    events.save(event);
  }

  @Transactional(readOnly = true)
  public ReplanEvent requireRetryable(int userId, int eventId) {
    ReplanEvent event = events.findByIdAndUserId(eventId, userId).orElseThrow(this::notFound);
    if (event.userDecision() != null || event.proposedPlanVersion() != null) {
      throw new ApiException(HttpStatus.CONFLICT, "REPLAN_NOT_RETRYABLE", "재시도할 수 없는 이벤트입니다.");
    }
    return event;
  }

  private FinancialGoal requireGoal(int userId, int goalId) {
    return goals.findByIdAndUserId(goalId, userId).orElseThrow(this::notFound);
  }

  private ReplanEventResponse response(ReplanEvent event) {
    return new ReplanEventResponse(
        event.id(),
        event.triggerType(),
        event.triggerDetails(),
        summary(event.sourcePlanVersion()),
        event.proposedPlanVersion() == null ? null : summary(event.proposedPlanVersion()),
        event.userDecision(),
        event.createdAt(),
        event.decidedAt());
  }

  private PlanVersionSummary summary(PlanVersion plan) {
    return new PlanVersionSummary(
        plan.id(),
        plan.versionNo(),
        plan.generationType(),
        plan.status(),
        plan.asOfDate(),
        plan.infeasibleReason(),
        plan.createdAt(),
        plan.activatedAt());
  }

  private ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원이 없습니다.");
  }

  public record ReplanRequestResult(int eventId, PlanCreation plan) {}
}

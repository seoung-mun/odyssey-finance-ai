package com.dacon.core.plan;

import com.dacon.core.goal.FinancialGoal;
import com.dacon.core.goal.FinancialGoalRepository;
import com.dacon.core.transaction.Transaction;
import com.dacon.core.transaction.TransactionImportedEvent;
import com.dacon.core.transaction.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 거래 commit과 KST 월간 시각에서 자동 재계획 조건을 평가한다. */
@Service
public class ReplanAutomationService {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private final TransactionRepository transactions;
  private final FinancialGoalRepository goals;
  private final PlanVersionRepository plans;
  private final PlanOptionRepository options;
  private final ReplanEventRepository events;
  private final ReplanService replans;
  private final ObjectMapper mapper;

  public ReplanAutomationService(
      TransactionRepository transactions,
      FinancialGoalRepository goals,
      PlanVersionRepository plans,
      PlanOptionRepository options,
      ReplanEventRepository events,
      ReplanService replans,
      ObjectMapper mapper) {
    this.transactions = transactions;
    this.goals = goals;
    this.plans = plans;
    this.options = options;
    this.events = events;
    this.replans = replans;
    this.mapper = mapper;
  }

  @EventListener
  public void afterImport(TransactionImportedEvent imported) {
    FinancialGoal goal = goals.findFirstByUserIdAndStatus(imported.userId(), "ACTIVE").orElse(null);
    if (goal == null || suppressed(goal, LocalDate.now(KST))) {
      return;
    }
    for (long paymentId : imported.paymentIds()) {
      evaluateShock(imported.userId(), goal, paymentId);
    }
    evaluateDrift(imported.userId(), goal, LocalDate.now(KST));
  }

  void evaluateShock(int userId, FinancialGoal goal, long paymentId) {
    Transaction payment = transactions.findByIdAndUserId(paymentId, userId).orElse(null);
    if (payment == null || !"PAYMENT".equals(payment.transactionType())) {
      return;
    }
    List<Long> sample = transactions.findPreviousVariablePaymentAmounts(userId, paymentId);
    if (!ReplanTriggerPolicy.shock(sample, payment.amount())) {
      return;
    }
    List<Long> sorted = sample.stream().sorted().toList();
    long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    ObjectNode details = mapper.createObjectNode();
    details
        .put("amount", payment.amount())
        .put("p95", p95)
        .put("threshold", ReplanTriggerPolicy.shockThreshold(p95));
    details.put("transactionId", paymentId);
    trigger(userId, goal.id(), "LARGE_UNEXPECTED_TRANSACTION", details, paymentId);
  }

  void evaluateDrift(int userId, FinancialGoal goal, LocalDate today) {
    PlanVersion active =
        plans.findFirstByGoalIdAndStatusOrderByVersionNoDesc(goal.id(), "ACTIVE").orElse(null);
    if (active == null) {
      return;
    }
    PlanOption option = options.findByPlanVersionIdAndSelectedAtIsNotNull(active.id()).orElse(null);
    if (option == null) {
      return;
    }
    OffsetDateTime from = YearMonth.from(today).atDay(1).atStartOfDay(KST).toOffsetDateTime();
    long actual =
        transactions.monthlySummary(userId, from, from.plusMonths(1)).stream()
            .mapToLong(TransactionRepository.MonthlySummaryView::getAdjustedConsumption)
            .sum();
    if (!ReplanTriggerPolicy.drift(today, option.recommendedMonthlySpending(), actual)) {
      return;
    }
    Instant dayStart = today.atStartOfDay(KST).toInstant();
    if (events.existsByGoalIdAndTriggerTypeAndCreatedAtBetween(
        goal.id(), "CUMULATIVE_OVERSPENDING", dayStart, dayStart.plusSeconds(86_400))) {
      return;
    }
    long planned =
        ReplanTriggerPolicy.plannedCumulative(option.recommendedMonthlySpending(), today);
    ObjectNode details = mapper.createObjectNode();
    details
        .put("checkpointDay", today.getDayOfMonth())
        .put("actualCumulative", actual)
        .put("plannedCumulative", planned)
        .put("ratio", planned == 0 ? 0 : actual / (double) planned);
    trigger(userId, goal.id(), "CUMULATIVE_OVERSPENDING", details, null);
  }

  @Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Seoul")
  public void monthly() {
    LocalDate today = LocalDate.now(KST);
    for (FinancialGoal goal : goals.findByStatus("ACTIVE")) {
      ObjectNode details = mapper.createObjectNode();
      details
          .put("scheduleMonth", YearMonth.from(today).toString())
          .put("asOfDate", today.toString());
      trigger(goal.userId(), goal.id(), "MONTHLY_REGULAR", details, null);
    }
  }

  private void trigger(
      int userId, int goalId, String type, ObjectNode details, Long transactionId) {
    try {
      replans.trigger(userId, goalId, type, details, transactionId, "auto-" + type.toLowerCase());
    } catch (DataIntegrityViolationException ignored) {
      // DB unique index가 동시/중복 trigger의 멱등성을 확정한다.
    }
  }

  private boolean suppressed(FinancialGoal goal, LocalDate today) {
    return goal.spendingReplanSuppressedUntil() != null
        && !goal.spendingReplanSuppressedUntil().isBefore(today);
  }
}

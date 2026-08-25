package com.dacon.core.plan;

import com.dacon.core.goal.FinancialGoal;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 계획 입력 snapshot과 상태를 append-only 버전으로 저장한다. */
@Entity
@Table(name = "plan_versions")
public class PlanVersion {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "goal_id", nullable = false)
  private FinancialGoal goal;

  private int versionNo;
  private String generationType;
  private String status;
  private LocalDate asOfDate;
  private long monthlyIncomeSnapshot;
  private long monthlyFixedCostSnapshot;
  private long targetAmountSnapshot;
  private long currentSavedSnapshot;
  private LocalDate targetDateSnapshot;
  private long availableVariableBudget;
  private long currentAvgVariableSpending;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode policySnapshot;

  private String explanationStatus = "PENDING";
  private String explanationText;
  private String explanationModel;
  private int explanationRetryCount;
  private String explanationPromptVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode explanationFailedNumbers;

  private Instant explanationGeneratedAt;
  private String infeasibleReason;
  private Instant createdAt;
  private Instant activatedAt;

  protected PlanVersion() {}

  /** 계산 입력 snapshot으로 계획 버전을 만든다. */
  public PlanVersion(
      FinancialGoal goal,
      int versionNo,
      String generationType,
      String status,
      LocalDate asOfDate,
      long monthlyIncome,
      long monthlyFixedCost,
      long targetAmount,
      long currentSaved,
      LocalDate targetDate,
      long availableVariableBudget,
      long currentAverage,
      JsonNode policySnapshot,
      String promptVersion,
      String infeasibleReason,
      JsonNode emptyArray) {
    this.goal = goal;
    this.versionNo = versionNo;
    this.generationType = generationType;
    this.status = status;
    this.asOfDate = asOfDate;
    monthlyIncomeSnapshot = monthlyIncome;
    monthlyFixedCostSnapshot = monthlyFixedCost;
    targetAmountSnapshot = targetAmount;
    currentSavedSnapshot = currentSaved;
    targetDateSnapshot = targetDate;
    this.availableVariableBudget = availableVariableBudget;
    currentAvgVariableSpending = currentAverage;
    this.policySnapshot = policySnapshot;
    explanationPromptVersion = promptVersion;
    this.infeasibleReason = infeasibleReason;
    explanationFailedNumbers = emptyArray;
    createdAt = Instant.now();
  }

  public void markStale() {
    status = "STALE";
  }

  public void supersede() {
    status = "SUPERSEDED";
  }

  public void activate(Instant now) {
    status = "ACTIVE";
    activatedAt = now;
  }

  public boolean beginExplanation() {
    if (!"PENDING".equals(explanationStatus)) {
      return false;
    }
    explanationStatus = "PROCESSING";
    return true;
  }

  public void completeExplanation(
      String status,
      String text,
      String model,
      int retryCount,
      JsonNode failedNumbers,
      Instant generatedAt) {
    explanationStatus = status;
    explanationText = text;
    explanationModel = model;
    explanationRetryCount = retryCount;
    explanationFailedNumbers = failedNumbers;
    explanationGeneratedAt = generatedAt;
  }

  public void fallbackExplanation(String text, Instant now, JsonNode emptyArray) {
    completeExplanation("FALLBACK", text, null, 0, emptyArray, now);
  }

  public int id() {
    return id;
  }

  public FinancialGoal goal() {
    return goal;
  }

  public int versionNo() {
    return versionNo;
  }

  public String generationType() {
    return generationType;
  }

  public String status() {
    return status;
  }

  public LocalDate asOfDate() {
    return asOfDate;
  }

  public long monthlyIncomeSnapshot() {
    return monthlyIncomeSnapshot;
  }

  public long monthlyFixedCostSnapshot() {
    return monthlyFixedCostSnapshot;
  }

  public long targetAmountSnapshot() {
    return targetAmountSnapshot;
  }

  public long currentSavedSnapshot() {
    return currentSavedSnapshot;
  }

  public LocalDate targetDateSnapshot() {
    return targetDateSnapshot;
  }

  public long availableVariableBudget() {
    return availableVariableBudget;
  }

  public long currentAvgVariableSpending() {
    return currentAvgVariableSpending;
  }

  public String explanationStatus() {
    return explanationStatus;
  }

  public String explanationText() {
    return explanationText;
  }

  public String explanationModel() {
    return explanationModel;
  }

  public int explanationRetryCount() {
    return explanationRetryCount;
  }

  public String explanationPromptVersion() {
    return explanationPromptVersion;
  }

  public JsonNode explanationFailedNumbers() {
    return explanationFailedNumbers;
  }

  public Instant explanationGeneratedAt() {
    return explanationGeneratedAt;
  }

  public String infeasibleReason() {
    return infeasibleReason;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant activatedAt() {
    return activatedAt;
  }
}

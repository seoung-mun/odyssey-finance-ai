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

/**
 * 목표별 계획 입력 스냅샷, 계획 상태, 비동기 설명 상태를 append-only 버전으로 저장하는 엔티티다.
 *
 * <p>계획 선택은 {@code PROPOSED -> ACTIVE}, 기존 활성 계획은 {@code ACTIVE -> SUPERSEDED}, 새 제안 생성은 이전 제안을
 * {@code STALE}로 전이한다. 설명 상태는 계획 계산과 독립적으로 관리된다.
 */
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

  /** JPA가 엔티티를 복원할 때만 사용하는 생성자다. */
  protected PlanVersion() {}

  /**
   * 계산 당시 목표·프로필·정책 값을 복사해 새 계획 버전을 만든다.
   *
   * @param goal 이 버전을 소유하는 금융 목표
   * @param versionNo 목표 안에서 증가하는 양수 버전 번호
   * @param generationType 최초 생성 또는 재계획 사유를 나타내는 종류
   * @param status 계산 결과에 따른 최초 계획 상태
   * @param asOfDate 계산 기준일
   * @param monthlyIncome 계산 당시 월 소득
   * @param monthlyFixedCost 계산 당시 월 고정비
   * @param targetAmount 계산 당시 목표 금액
   * @param currentSaved 계산 당시 저축 금액
   * @param targetDate 계산 당시 목표 달성 예정일
   * @param availableVariableBudget 계산에 사용한 전체 가용 유동지출
   * @param currentAverage 계산 당시 과거 월평균 유동지출
   * @param policySnapshot 계산 당시 정책 파라미터 JSON
   * @param promptVersion 후속 설명 생성에 사용할 프롬프트 버전; 계산 불가능 계획이면 {@code null}
   * @param infeasibleReason 계산 불가능 사유; 계산 가능한 계획이면 {@code null}
   * @param emptyArray 설명 검증 실패 숫자의 초기 빈 JSON 배열
   */
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

  /** 아직 선택되지 않은 이전 제안을 새 제안 생성 전에 {@code STALE}로 전이한다. */
  public void markStale() {
    status = "STALE";
  }

  /** 기존 활성 계획을 새 계획 활성화 전에 {@code SUPERSEDED}로 전이한다. */
  public void supersede() {
    status = "SUPERSEDED";
  }

  /** 사용자가 기존 계획 유지를 선택한 제안을 거절 상태로 전이한다. */
  public void reject() {
    status = "REJECTED";
  }

  /**
   * 선택된 제안을 {@code ACTIVE}로 전이하고 활성화 시각을 기록한다.
   *
   * @param now 선택 옵션과 공유할 활성화 시각
   */
  public void activate(Instant now) {
    status = "ACTIVE";
    activatedAt = now;
  }

  /**
   * 설명 작업을 {@code PENDING}에서 {@code PROCESSING}으로 한 번만 전이한다.
   *
   * @return 전이가 수행되면 {@code true}, 이미 다른 상태이면 {@code false}
   */
  public boolean beginExplanation() {
    if (!"PENDING".equals(explanationStatus)) {
      return false;
    }
    explanationStatus = "PROCESSING";
    return true;
  }

  /**
   * 검증이 끝난 설명 결과와 생성 메타데이터를 최종 상태로 저장한다.
   *
   * @param status {@code READY}, {@code FALLBACK} 또는 {@code FAILED} 최종 상태
   * @param text 사용자에게 제공할 설명문
   * @param model 설명 생성 모델; fallback이면 {@code null}
   * @param retryCount 설명 숫자 검증 재시도 횟수
   * @param failedNumbers 검증에 실패한 숫자 문자열의 JSON 배열
   * @param generatedAt 설명 결과 생성 시각
   */
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

  /**
   * 숫자 없는 고정 문구로 설명 상태를 {@code FALLBACK}으로 마감한다.
   *
   * @param text 사용자에게 제공할 fallback 문구
   * @param now fallback 결정 시각
   * @param emptyArray 빈 실패 숫자 JSON 배열
   */
  public void fallbackExplanation(String text, Instant now, JsonNode emptyArray) {
    completeExplanation("FALLBACK", text, null, 0, emptyArray, now);
  }

  /** {@return 영속화된 계획 버전 식별자} */
  public int id() {
    return id;
  }

  /** {@return 이 계획 버전을 소유하는 금융 목표} */
  public FinancialGoal goal() {
    return goal;
  }

  /** {@return 목표 안에서의 계획 버전 번호} */
  public int versionNo() {
    return versionNo;
  }

  /** {@return 최초 생성 또는 재계획 종류} */
  public String generationType() {
    return generationType;
  }

  /** {@return 현재 계획 생명주기 상태} */
  public String status() {
    return status;
  }

  /** {@return 계획 입력을 확정한 기준일} */
  public LocalDate asOfDate() {
    return asOfDate;
  }

  /** {@return 계산 당시 원 단위 월 소득} */
  public long monthlyIncomeSnapshot() {
    return monthlyIncomeSnapshot;
  }

  /** {@return 계산 당시 원 단위 월 고정비} */
  public long monthlyFixedCostSnapshot() {
    return monthlyFixedCostSnapshot;
  }

  /** {@return 계산 당시 원 단위 목표 금액} */
  public long targetAmountSnapshot() {
    return targetAmountSnapshot;
  }

  /** {@return 계산 당시 원 단위 저축 금액} */
  public long currentSavedSnapshot() {
    return currentSavedSnapshot;
  }

  /** {@return 계산 당시 목표 달성 예정일} */
  public LocalDate targetDateSnapshot() {
    return targetDateSnapshot;
  }

  /** {@return 계산에 사용한 원 단위 전체 가용 유동지출} */
  public long availableVariableBudget() {
    return availableVariableBudget;
  }

  /** {@return 계산 당시 원 단위 월평균 유동지출} */
  public long currentAvgVariableSpending() {
    return currentAvgVariableSpending;
  }

  /** {@return 현재 설명 처리 상태} */
  public String explanationStatus() {
    return explanationStatus;
  }

  /** {@return 최종 설명문; 아직 생성되지 않았으면 {@code null}} */
  public String explanationText() {
    return explanationText;
  }

  /** {@return 설명 생성 모델; 미생성 또는 fallback이면 {@code null}} */
  public String explanationModel() {
    return explanationModel;
  }

  /** {@return 숫자 검증을 위해 수행한 설명 재시도 횟수} */
  public int explanationRetryCount() {
    return explanationRetryCount;
  }

  /** {@return 이 계획 설명에 고정된 프롬프트 버전; 계산 불가능 계획이면 {@code null}} */
  public String explanationPromptVersion() {
    return explanationPromptVersion;
  }

  /** {@return 검증에 실패한 숫자 문자열의 JSON 배열} */
  public JsonNode explanationFailedNumbers() {
    return explanationFailedNumbers;
  }

  /** {@return 설명이 최종 상태가 된 시각; 미완료이면 {@code null}} */
  public Instant explanationGeneratedAt() {
    return explanationGeneratedAt;
  }

  /** {@return 계산 불가능 사유; 계산 가능한 계획이면 {@code null}} */
  public String infeasibleReason() {
    return infeasibleReason;
  }

  /** {@return 계획 버전 생성 시각} */
  public Instant createdAt() {
    return createdAt;
  }

  /** {@return 사용자가 옵션을 선택해 계획이 활성화된 시각; 미활성이면 {@code null}} */
  public Instant activatedAt() {
    return activatedAt;
  }
}

package com.dacon.core.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 계산 엔진이 확정한 계획 선택지를 계획 버전에 귀속해 저장하는 엔티티다.
 *
 * <p>선택 시각이 존재하는 옵션만 사용자가 채택한 옵션이며, 실제 계획 활성화는 {@link PlanVersion}과 같은 트랜잭션에서 수행한다.
 */
@Entity
@Table(name = "plan_options")
public class PlanOption {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "plan_version_id", nullable = false)
  private PlanVersion planVersion;

  private String optionType;

  @Column(name = "nominal_level", precision = 4, scale = 3)
  private BigDecimal nominalLevel;

  private long recommendedMonthlySpending;

  @Column(name = "required_reduction_rate", nullable = false, precision = 23, scale = 4)
  private BigDecimal requiredReductionRate;

  @Column(name = "simulation_coverage", nullable = false, precision = 5, scale = 4)
  private BigDecimal simulationCoverage;

  @Column(name = "historical_feasibility_ratio", nullable = false, precision = 5, scale = 4)
  private BigDecimal historicalFeasibilityRatio;

  private boolean aggressiveWarning;

  @Column(name = "effective_max_reduction_rate", nullable = false, precision = 5, scale = 4)
  private BigDecimal effectiveMaxReductionRate;

  private boolean floorApplied;
  private boolean targetCoverageMet;
  private Instant selectedAt;
  private Instant createdAt;

  /** JPA가 엔티티를 복원할 때만 사용하는 생성자다. */
  protected PlanOption() {}

  /**
   * 검증이 끝난 FastAPI 선택지 JSON을 계획 버전에 귀속시킨다.
   *
   * @param planVersion 선택지를 소유하는 계획 버전
   * @param option 선택지 종류, 금액, 비율과 경고 값을 포함한 계산 결과
   */
  public PlanOption(PlanVersion planVersion, com.fasterxml.jackson.databind.JsonNode option) {
    this.planVersion = planVersion;
    optionType = option.path("optionType").asText();
    nominalLevel =
        option.path("nominalLevel").isNull() ? null : option.path("nominalLevel").decimalValue();
    recommendedMonthlySpending = option.path("recommendedMonthlySpending").asLong();
    requiredReductionRate = option.path("requiredReductionRate").decimalValue();
    simulationCoverage = option.path("simulationCoverage").decimalValue();
    historicalFeasibilityRatio = option.path("historicalFeasibilityRatio").decimalValue();
    aggressiveWarning = option.path("aggressiveWarning").asBoolean();
    effectiveMaxReductionRate = option.path("effectiveMaxReductionRate").decimalValue();
    floorApplied = option.path("floorApplied").asBoolean();
    targetCoverageMet = option.path("targetCoverageMet").asBoolean();
    createdAt = Instant.now();
  }

  /**
   * 사용자가 이 옵션을 선택한 시각을 기록한다.
   *
   * @param now 계획 활성화와 공유할 선택 시각
   */
  public void select(Instant now) {
    selectedAt = now;
  }

  /** {@return 영속화된 계획 옵션 식별자} */
  public int id() {
    return id;
  }

  /** {@return 이 옵션을 소유하는 계획 버전} */
  public PlanVersion planVersion() {
    return planVersion;
  }

  /** {@return {@code PRESET} 또는 {@code CUSTOM} 선택지 종류} */
  public String optionType() {
    return optionType;
  }

  /** {@return PRESET 명목 수준; CUSTOM 선택지는 {@code null}} */
  public BigDecimal nominalLevel() {
    return nominalLevel;
  }

  /** {@return 엔진이 확정한 원 단위 권장 월 유동지출} */
  public long recommendedMonthlySpending() {
    return recommendedMonthlySpending;
  }

  /** {@return 현재 평균 지출 대비 필요한 절감률} */
  public BigDecimal requiredReductionRate() {
    return requiredReductionRate;
  }

  /** {@return 시뮬레이션 경로 중 목표에 도달한 비율} */
  public BigDecimal simulationCoverage() {
    return simulationCoverage;
  }

  /** {@return 과거 지출 중 권장 지출 이하였던 월의 비율} */
  public BigDecimal historicalFeasibilityRatio() {
    return historicalFeasibilityRatio;
  }

  /** {@return 정책상 과도한 절감으로 경고해야 하는지 여부} */
  public boolean aggressiveWarning() {
    return aggressiveWarning;
  }

  /** {@return 지출 하한을 반영한 최대 허용 절감률} */
  public BigDecimal effectiveMaxReductionRate() {
    return effectiveMaxReductionRate;
  }

  /** {@return 지출 하한 때문에 권장 지출액이 조정됐는지 여부} */
  public boolean floorApplied() {
    return floorApplied;
  }

  /** {@return 지출 하한 적용 뒤에도 목표 달성 커버리지를 충족했는지 여부} */
  public boolean targetCoverageMet() {
    return targetCoverageMet;
  }

  /** {@return 사용자 선택 시각; 아직 선택되지 않았으면 {@code null}} */
  public Instant selectedAt() {
    return selectedAt;
  }
}

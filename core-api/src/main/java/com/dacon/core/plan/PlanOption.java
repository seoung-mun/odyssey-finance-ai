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

/** 계획 선택지와 plan 외래키 관계를 저장한다. */
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

  protected PlanOption() {}

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

  public void select(Instant now) {
    selectedAt = now;
  }

  public int id() {
    return id;
  }

  public PlanVersion planVersion() {
    return planVersion;
  }

  public String optionType() {
    return optionType;
  }

  public BigDecimal nominalLevel() {
    return nominalLevel;
  }

  public long recommendedMonthlySpending() {
    return recommendedMonthlySpending;
  }

  public BigDecimal requiredReductionRate() {
    return requiredReductionRate;
  }

  public BigDecimal simulationCoverage() {
    return simulationCoverage;
  }

  public BigDecimal historicalFeasibilityRatio() {
    return historicalFeasibilityRatio;
  }

  public boolean aggressiveWarning() {
    return aggressiveWarning;
  }

  public BigDecimal effectiveMaxReductionRate() {
    return effectiveMaxReductionRate;
  }

  public boolean floorApplied() {
    return floorApplied;
  }

  public boolean targetCoverageMet() {
    return targetCoverageMet;
  }

  public Instant selectedAt() {
    return selectedAt;
  }
}

package com.dacon.core.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/** 오프라인 생성된 데모 시나리오의 공개 표시 필드다. */
@Entity
@Table(name = "demo_scenarios")
@IdClass(DemoScenario.DemoScenarioId.class)
public class DemoScenario {
  @Id private String testerId;
  @Id private int scenarioVersion;
  private String displayName;
  private String description;
  private String ageGroup;

  @Column(precision = 19, scale = 0)
  private BigDecimal monthlyIncome;

  @Column(precision = 19, scale = 0)
  private BigDecimal monthlyFixedCost;

  private String goalName;

  @Column(precision = 19, scale = 0)
  private BigDecimal goalTargetAmount;

  private short goalMonths;

  protected DemoScenario() {}

  DemoScenario(
      String testerId,
      int scenarioVersion,
      String displayName,
      String description,
      String ageGroup,
      BigDecimal monthlyIncome,
      BigDecimal monthlyFixedCost,
      String goalName,
      BigDecimal goalTargetAmount,
      short goalMonths) {
    this.testerId = testerId;
    this.scenarioVersion = scenarioVersion;
    this.displayName = displayName;
    this.description = description;
    this.ageGroup = ageGroup;
    this.monthlyIncome = monthlyIncome;
    this.monthlyFixedCost = monthlyFixedCost;
    this.goalName = goalName;
    this.goalTargetAmount = goalTargetAmount;
    this.goalMonths = goalMonths;
  }

  DemoDtos.DemoTester response() {
    return new DemoDtos.DemoTester(
        testerId,
        displayName,
        description,
        ageGroup,
        monthlyIncome.longValueExact(),
        monthlyFixedCost.longValueExact(),
        goalName,
        goalTargetAmount.longValueExact(),
        goalMonths);
  }

  public static final class DemoScenarioId implements Serializable {
    private String testerId;
    private int scenarioVersion;

    public DemoScenarioId() {}

    public DemoScenarioId(String testerId, int scenarioVersion) {
      this.testerId = testerId;
      this.scenarioVersion = scenarioVersion;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof DemoScenarioId id
          && scenarioVersion == id.scenarioVersion
          && Objects.equals(testerId, id.testerId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(testerId, scenarioVersion);
    }
  }
}

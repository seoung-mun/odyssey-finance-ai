package com.dacon.core.plan;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** FastAPI 확정 계산의 입력·결과 JSON과 plan 일대일 관계를 저장한다. */
@Entity
@Table(name = "simulation_runs")
public class SimulationRun {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "plan_version_id", nullable = false, unique = true)
  private PlanVersion planVersion;

  private String method;
  private int nPaths;
  private long randomSeed;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode inputSnapshot;

  private String inputHash;
  private String engineVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode resultSummary;

  private Instant createdAt;

  protected SimulationRun() {}

  public SimulationRun(
      PlanVersion planVersion,
      String method,
      int nPaths,
      long randomSeed,
      JsonNode inputSnapshot,
      String inputHash,
      String engineVersion,
      JsonNode resultSummary) {
    this.planVersion = planVersion;
    this.method = method;
    this.nPaths = nPaths;
    this.randomSeed = randomSeed;
    this.inputSnapshot = inputSnapshot;
    this.inputHash = inputHash;
    this.engineVersion = engineVersion;
    this.resultSummary = resultSummary;
    createdAt = Instant.now();
  }

  public String method() {
    return method;
  }

  public int nPaths() {
    return nPaths;
  }

  public String inputHash() {
    return inputHash;
  }

  public JsonNode inputSnapshot() {
    return inputSnapshot;
  }

  public String engineVersion() {
    return engineVersion;
  }

  public JsonNode resultSummary() {
    return resultSummary;
  }

  public Instant createdAt() {
    return createdAt;
  }
}

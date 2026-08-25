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

/**
 * FastAPI가 확정한 시뮬레이션 입력·결과와 계획 버전의 일대일 관계를 저장하는 엔티티다.
 *
 * <p>입력 스냅샷, 해시, 난수 seed와 엔진 버전을 함께 보존해 과거 계산의 조건을 추적할 수 있게 한다.
 */
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

  /** JPA가 엔티티를 복원할 때만 사용하는 생성자다. */
  protected SimulationRun() {}

  /**
   * 검증된 계산 응답의 재현 메타데이터와 JSON 스냅샷을 계획 버전에 귀속시킨다.
   *
   * @param planVersion 이 실행 결과를 소유하는 계획 버전
   * @param method 시뮬레이션 방법
   * @param nPaths 몬테카를로 경로 수
   * @param randomSeed 계산에 사용한 난수 seed
   * @param inputSnapshot FastAPI가 확정해 반환한 계산 입력 JSON
   * @param inputHash 입력 스냅샷의 SHA-256 해시
   * @param engineVersion 계산 엔진 버전
   * @param resultSummary 시뮬레이션 요약과 해석된 지출 하한 JSON
   */
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

  /** {@return 시뮬레이션 방법} */
  public String method() {
    return method;
  }

  /** {@return 몬테카를로 경로 수} */
  public int nPaths() {
    return nPaths;
  }

  /** {@return 계산 입력의 SHA-256 해시} */
  public String inputHash() {
    return inputHash;
  }

  /** {@return FastAPI가 확정한 계산 입력 JSON} */
  public JsonNode inputSnapshot() {
    return inputSnapshot;
  }

  /** {@return 계산을 수행한 엔진 버전} */
  public String engineVersion() {
    return engineVersion;
  }

  /** {@return 시뮬레이션 요약과 해석된 지출 하한 JSON} */
  public JsonNode resultSummary() {
    return resultSummary;
  }

  /** {@return 실행 결과 저장 시각} */
  public Instant createdAt() {
    return createdAt;
  }
}

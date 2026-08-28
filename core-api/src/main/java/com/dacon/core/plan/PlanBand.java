package com.dacon.core.plan;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/** 하나의 계획 옵션에 속한 월별 누적 저축 분위수 밴드를 영속화하는 엔티티다. */
@Entity
@Table(name = "plan_option_percentile_bands")
public class PlanBand {
  @EmbeddedId private PlanBandId id;

  @MapsId("planOptionId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "plan_option_id", nullable = false)
  private PlanOption option;

  @Column(name = "p10_value", nullable = false)
  private long p10Value;

  @Column(name = "p25_value", nullable = false)
  private long p25Value;

  @Column(name = "p50_value", nullable = false)
  private long p50Value;

  @Column(name = "p75_value", nullable = false)
  private long p75Value;

  @Column(name = "p90_value", nullable = false)
  private long p90Value;

  /** JPA가 엔티티를 복원할 때만 사용하는 생성자다. */
  protected PlanBand() {}

  /**
   * 검증이 끝난 FastAPI 밴드 JSON을 주어진 옵션에 귀속시킨다.
   *
   * @param option 밴드를 소유하는 저장 완료 계획 옵션
   * @param band 월 인덱스, 지표 종류와 p10~p90 값을 포함한 계산 결과
   */
  public PlanBand(PlanOption option, com.fasterxml.jackson.databind.JsonNode band) {
    this.option = option;
    id =
        new PlanBandId(
            option.id(), band.path("monthIndex").asInt(), band.path("metricType").asText());
    p10Value = band.path("p10").asLong();
    p25Value = band.path("p25").asLong();
    p50Value = band.path("p50").asLong();
    p75Value = band.path("p75").asLong();
    p90Value = band.path("p90").asLong();
  }

  /** {@return 계획 시작을 1로 세는 밴드의 월 인덱스} */
  public int monthIndex() {
    return id.monthIndex();
  }

  /** {@return 현재는 {@code CUMULATIVE_SAVINGS}인 밴드 지표 종류} */
  public String metricType() {
    return id.metricType();
  }

  /** {@return 누적 저축 분포의 10번째 백분위 원 단위 값} */
  public long p10Value() {
    return p10Value;
  }

  /** {@return 누적 저축 분포의 25번째 백분위 원 단위 값} */
  public long p25Value() {
    return p25Value;
  }

  /** {@return 누적 저축 분포의 중앙값인 원 단위 값} */
  public long p50Value() {
    return p50Value;
  }

  /** {@return 누적 저축 분포의 75번째 백분위 원 단위 값} */
  public long p75Value() {
    return p75Value;
  }

  /** {@return 누적 저축 분포의 90번째 백분위 원 단위 값} */
  public long p90Value() {
    return p90Value;
  }
}

package com.dacon.core.plan;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/** 옵션별 월 분위수 band와 plan option 관계를 저장한다. */
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

  protected PlanBand() {}

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

  public int monthIndex() {
    return id.monthIndex();
  }

  public String metricType() {
    return id.metricType();
  }

  public long p10Value() {
    return p10Value;
  }

  public long p25Value() {
    return p25Value;
  }

  public long p50Value() {
    return p50Value;
  }

  public long p75Value() {
    return p75Value;
  }

  public long p90Value() {
    return p90Value;
  }
}

package com.dacon.core.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** 분위수 band 복합 기본키다. */
@Embeddable
public record PlanBandId(
    @Column(name = "plan_option_id") Integer planOptionId,
    @Column(name = "month_index") Integer monthIndex,
    @Column(name = "metric_type") String metricType)
    implements Serializable {
  public PlanBandId() {
    this(null, null, null);
  }
}

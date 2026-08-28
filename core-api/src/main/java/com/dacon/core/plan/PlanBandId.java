package com.dacon.core.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;

/**
 * 계획 옵션, 월, 지표 종류로 분위수 밴드를 유일하게 식별하는 복합 기본키다.
 *
 * @param planOptionId 밴드를 소유하는 계획 옵션 식별자
 * @param monthIndex 계획 시작을 1로 세는 월 인덱스
 * @param metricType 분위수 값이 표현하는 지표 종류
 */
@Embeddable
public record PlanBandId(
    @Column(name = "plan_option_id") Integer planOptionId,
    @Column(name = "month_index") Integer monthIndex,
    @Column(name = "metric_type") String metricType)
    implements Serializable {
  /** JPA가 복합 키를 복원할 때 사용할 모든 구성요소가 {@code null}인 인스턴스를 만든다. */
  public PlanBandId() {
    this(null, null, null);
  }
}

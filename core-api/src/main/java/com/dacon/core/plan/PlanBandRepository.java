package com.dacon.core.plan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 옵션별 분위수 band를 월 순서로 조회·저장한다. */
public interface PlanBandRepository extends JpaRepository<PlanBand, PlanBandId> {
  List<PlanBand> findByOptionIdOrderByIdMonthIndex(int optionId);
}

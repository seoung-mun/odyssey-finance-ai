package com.dacon.core.plan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 계획 옵션별 분위수 밴드를 조회·저장하는 Spring Data 저장소다. */
public interface PlanBandRepository extends JpaRepository<PlanBand, PlanBandId> {
  /**
   * 한 옵션의 밴드를 계획 월 오름차순으로 조회한다.
   *
   * @param optionId 계획 옵션 식별자
   * @return 월 순서가 보장된 밴드 목록
   */
  List<PlanBand> findByOptionIdOrderByIdMonthIndex(int optionId);
}

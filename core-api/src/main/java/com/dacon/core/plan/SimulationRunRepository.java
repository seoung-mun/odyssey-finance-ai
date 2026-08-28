package com.dacon.core.plan;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 계획 버전별 단일 시뮬레이션 실행을 조회·저장하는 Spring Data 저장소다. */
public interface SimulationRunRepository extends JpaRepository<SimulationRun, Integer> {
  /**
   * 계획 버전에 귀속된 유일한 시뮬레이션 실행을 조회한다.
   *
   * @param planVersionId 계획 버전 식별자
   * @return 계산 가능한 계획에 저장된 실행 결과
   */
  Optional<SimulationRun> findByPlanVersionId(int planVersionId);
}

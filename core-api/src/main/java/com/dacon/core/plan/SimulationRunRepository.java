package com.dacon.core.plan;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 계획별 단일 simulation을 Spring Data에 저장한다. */
public interface SimulationRunRepository extends JpaRepository<SimulationRun, Integer> {
  Optional<SimulationRun> findByPlanVersionId(int planVersionId);
}

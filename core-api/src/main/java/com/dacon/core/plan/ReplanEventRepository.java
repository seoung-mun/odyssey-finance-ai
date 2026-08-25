package com.dacon.core.plan;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 재계획 사건 조회·저장을 Spring Data에 위임한다. */
public interface ReplanEventRepository extends JpaRepository<ReplanEvent, Integer> {
  Optional<ReplanEvent> findByProposedPlanVersionId(int planVersionId);
}

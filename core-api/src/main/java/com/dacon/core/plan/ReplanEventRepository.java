package com.dacon.core.plan;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 재계획 사건을 조회·저장하는 Spring Data 저장소다. */
public interface ReplanEventRepository extends JpaRepository<ReplanEvent, Integer> {
  /**
   * 특정 제안 계획을 만든 재계획 사건을 조회한다.
   *
   * @param planVersionId 제안된 계획 버전 식별자
   * @return 연결된 재계획 사건; 사용자 직접 최초 생성이면 비어 있음
   */
  Optional<ReplanEvent> findByProposedPlanVersionId(int planVersionId);
}

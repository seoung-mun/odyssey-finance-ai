package com.dacon.core.plan;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 재계획 사건을 조회·저장하는 Spring Data 저장소다. */
public interface ReplanEventRepository extends JpaRepository<ReplanEvent, Integer> {
  /**
   * 특정 제안 계획을 만든 재계획 사건을 조회한다.
   *
   * @param planVersionId 제안된 계획 버전 식별자
   * @return 연결된 재계획 사건; 사용자 직접 최초 생성이면 비어 있음
   */
  Optional<ReplanEvent> findByProposedPlanVersionId(int planVersionId);

  List<ReplanEvent> findByGoalIdAndUserIdOrderByCreatedAtDesc(int goalId, int userId);

  Optional<ReplanEvent> findByIdAndUserId(int id, int userId);

  boolean existsByGoalIdAndTriggerTypeAndCreatedAtBetween(
      int goalId, String triggerType, Instant from, Instant to);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select event from ReplanEvent event where event.id=:id and event.user.id=:userId")
  Optional<ReplanEvent> findOwnedForUpdate(@Param("id") int id, @Param("userId") int userId);
}

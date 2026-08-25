package com.dacon.core.plan;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 계획 버전 조회·저장과 선택 잠금을 Spring Data에 위임한다. */
public interface PlanVersionRepository extends JpaRepository<PlanVersion, Integer> {
  List<PlanVersion> findByGoalIdOrderByVersionNoDesc(int goalId);

  List<PlanVersion> findByGoalIdAndStatusOrderByVersionNoDesc(int goalId, String status);

  Optional<PlanVersion> findByIdAndGoalUserId(int id, int userId);

  Optional<PlanVersion> findFirstByGoalIdAndStatusOrderByVersionNoDesc(int goalId, String status);

  @Query(
      "select coalesce(max(plan.versionNo), 0) from PlanVersion plan where plan.goal.id = :goalId")
  int findMaxVersionNo(@Param("goalId") int goalId);

  /** 옵션 선택 시 계획 버전을 잠근다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select plan from PlanVersion plan where plan.id = :planId and plan.goal.user.id = :userId")
  Optional<PlanVersion> findOwnedForUpdate(
      @Param("userId") int userId, @Param("planId") int planId);

  /** 설명 상태 변경 경합을 막기 위해 계획 버전을 잠근다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select plan from PlanVersion plan where plan.id = :planId")
  Optional<PlanVersion> findForUpdate(@Param("planId") long planId);

  List<PlanVersion> findByExplanationStatus(String status);
}

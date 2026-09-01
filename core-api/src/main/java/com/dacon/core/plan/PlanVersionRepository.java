package com.dacon.core.plan;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 계획 버전 조회·저장과 상태 전이 직렬화를 위한 잠금을 제공하는 Spring Data 저장소다. */
public interface PlanVersionRepository extends JpaRepository<PlanVersion, Integer> {
  /**
   * 목표의 전체 계획 이력을 최신 버전부터 찾는다.
   *
   * @param goalId 금융 목표 식별자
   * @return 버전 번호 내림차순 계획 목록
   */
  List<PlanVersion> findByGoalIdOrderByVersionNoDesc(int goalId);

  /**
   * 목표의 계획 이력을 상태로 제한해 찾는다.
   *
   * @param goalId 금융 목표 식별자
   * @param status 조회할 계획 상태
   * @return 상태가 일치하는 버전 번호 내림차순 계획 목록
   */
  List<PlanVersion> findByGoalIdAndStatusOrderByVersionNoDesc(int goalId, String status);

  /**
   * 목표 관계를 경유해 사용자 소유 계획을 찾는다.
   *
   * @param id 계획 버전 식별자
   * @param userId 목표 소유 사용자 식별자
   * @return 목표 관계로 사용자 소유권이 확인된 계획
   */
  Optional<PlanVersion> findByIdAndGoalUserId(int id, int userId);

  @Query(
      "select plan.goal.id from PlanVersion plan where plan.id = :planId and plan.goal.user.id = :userId")
  Optional<Integer> findOwnedGoalId(@Param("userId") int userId, @Param("planId") int planId);

  /**
   * 목표의 특정 상태 최신 계획을 찾는다.
   *
   * @param goalId 금융 목표 식별자
   * @param status 조회할 계획 상태
   * @return 조건에 맞는 가장 높은 버전의 계획
   */
  Optional<PlanVersion> findFirstByGoalIdAndStatusOrderByVersionNoDesc(int goalId, String status);

  /**
   * 목표별 다음 버전 번호 계산에 필요한 현재 최댓값을 구한다.
   *
   * @param goalId 금융 목표 식별자
   * @return 저장된 계획이 없으면 0, 있으면 최대 버전 번호
   */
  @Query(
      "select coalesce(max(plan.versionNo), 0) from PlanVersion plan where plan.goal.id = :goalId")
  int findMaxVersionNo(@Param("goalId") int goalId);

  /**
   * 옵션 선택 시 사용자 소유 계획을 비관적 쓰기 잠금으로 조회한다.
   *
   * @param userId 목표 소유 사용자 식별자
   * @param planId 잠글 계획 버전 식별자
   * @return 사용자에게 속한 계획
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select plan from PlanVersion plan where plan.id = :planId and plan.goal.user.id = :userId")
  Optional<PlanVersion> findOwnedForUpdate(
      @Param("userId") int userId, @Param("planId") int planId);

  /**
   * 설명 상태 변경 경합을 막기 위해 계획 행을 비관적 쓰기 잠금으로 조회한다.
   *
   * @param planId 잠글 계획 버전 식별자
   * @return 해당 계획 행
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select plan from PlanVersion plan where plan.id = :planId")
  Optional<PlanVersion> findForUpdate(@Param("planId") long planId);

  /**
   * 복구 또는 운영 조회를 위해 설명 상태가 같은 계획을 찾는다.
   *
   * @param status 조회할 설명 상태
   * @return 해당 설명 상태인 계획 목록
   */
  List<PlanVersion> findByExplanationStatus(String status);
}

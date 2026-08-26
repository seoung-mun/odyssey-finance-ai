package com.dacon.core.goal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 목표 조회·저장과 명시적 잠금을 Spring Data에 위임한다. */
public interface FinancialGoalRepository extends JpaRepository<FinancialGoal, Integer> {
  /**
   * 사용자의 목표 이력 전체를 화면 표시 순서로 조회한다.
   *
   * @param userId 목표 소유 사용자 ID
   * @return 사용자의 모든 목표를 최신 생성 순으로 정렬한 목록
   */
  List<FinancialGoal> findByUserIdOrderByCreatedAtDesc(int userId);

  /**
   * 사용자의 목표 이력을 상태로 제한해 조회한다.
   *
   * @param userId 목표 소유 사용자 ID
   * @param status 조회할 목표 상태
   * @return 사용자의 지정 상태 목표를 최신 생성 순으로 정렬한 목록
   */
  List<FinancialGoal> findByUserIdAndStatusOrderByCreatedAtDesc(int userId, String status);

  /**
   * 목표 ID와 사용자 ID를 함께 조건으로 삼아 다른 사용자의 목표를 숨긴다.
   *
   * @param id 목표 ID
   * @param userId 목표 소유 사용자 ID
   * @return ID와 사용자 소유권이 모두 일치하는 목표
   */
  Optional<FinancialGoal> findByIdAndUserId(int id, int userId);

  /**
   * 사용자의 지정 상태 목표 한 건을 존재 확인과 현재 목표 조회에 사용한다.
   *
   * @param userId 목표 소유 사용자 ID
   * @param status 조회할 목표 상태
   * @return 사용자에게 지정 상태 목표가 있으면 첫 행
   */
  Optional<FinancialGoal> findFirstByUserIdAndStatus(int userId, String status);

  /**
   * 사용자가 지정 상태 목표를 보유했는지만 엔티티 로딩 없이 확인한다.
   *
   * @param userId 목표 소유 사용자 ID
   * @param status 확인할 목표 상태
   * @return 사용자에게 지정 상태 목표가 하나라도 있으면 {@code true}
   */
  boolean existsByUserIdAndStatus(int userId, String status);

  List<FinancialGoal> findByStatus(String status);

  /**
   * 계산 결과 저장 전 목표를 비관적 쓰기 잠금하고 소유권과 ACTIVE 상태를 함께 확인한다.
   *
   * @param userId 인증된 사용자 ID
   * @param goalId 잠글 목표 ID
   * @return 조건을 모두 만족하면 잠긴 목표
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select goal from FinancialGoal goal where goal.id = :goalId and goal.user.id = :userId and"
          + " goal.status = 'ACTIVE'")
  Optional<FinancialGoal> findActiveForUpdate(
      @Param("userId") int userId, @Param("goalId") int goalId);
}
